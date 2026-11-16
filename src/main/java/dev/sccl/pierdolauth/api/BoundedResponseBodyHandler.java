package dev.sccl.pierdolauth.api;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Admits complete responses up to 1 MiB; never retains non-200 response text. */
final class BoundedResponseBodyHandler implements HttpResponse.BodyHandler<byte[]> {
    private static final int MAX_BYTES = 1_048_576;

    @Override public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo response) {
        return new Subscriber(response.statusCode() == 200);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final boolean retain;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private byte[] bytes;
        private int received;

        Subscriber(boolean retain) { this.retain = retain; }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || body.isDone()) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            incoming.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                // Check before allocating, copying, or admitting any bytes from this buffer.
                if (size > MAX_BYTES - received) {
                    subscription.cancel();
                    onError(new IOException());
                    return;
                }
                if (retain && size != 0) {
                    if (bytes == null) bytes = new byte[MAX_BYTES];
                    buffer.get(bytes, received, size);
                }
                received += size;
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable ignored) {
            if (body.isDone()) return;
            bytes = null;
            body.completeExceptionally(new IOException("Game-auth response unavailable"));
        }

        @Override public void onComplete() {
            if (body.isDone()) return;
            byte[] complete = bytes == null ? new byte[0]
                    : received == MAX_BYTES ? bytes : Arrays.copyOf(bytes, received);
            bytes = null;
            body.complete(complete);
        }
    }
}
