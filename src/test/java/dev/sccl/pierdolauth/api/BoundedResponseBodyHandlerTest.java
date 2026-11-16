package dev.sccl.pierdolauth.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedResponseBodyHandlerTest {
    @Test void completeOneMiBIsReturnedExactlyOnlyAfterEndOfBody() throws Exception {
        var subscriber = subscriber(200);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        byte[] expected = new byte[1_048_576];
        Arrays.fill(expected, (byte) 'x');
        subscriber.onNext(List.of(ByteBuffer.wrap(expected, 0, 17)));
        subscriber.onNext(List.of(ByteBuffer.wrap(expected, 17, expected.length - 17)));
        assertFalse(subscriber.getBody().toCompletableFuture().isDone(), "Partial bodies must not escape");
        assertFalse(subscription.cancelled);
        subscriber.onComplete();
        assertArrayEquals(expected, subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void oversizedSingleBufferIsCancelledBeforeAnyBodyAdmission(int status) {
        var subscriber = subscriber(status);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        ByteBuffer oversized = ByteBuffer.allocate(1_048_577);
        subscriber.onNext(List.of(oversized));
        assertTrue(subscription.cancelled, "Must cancel transport immediately on overflow");
        assertEquals(0, oversized.position(), "Overflowing input must not be copied/consumed before rejection");
        assertUnavailable(subscriber);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void byteAfterCapAndLateBuffersAreNeverAdmitted(int status) {
        var subscriber = subscriber(status);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(1_048_575)));
        ByteBuffer last = ByteBuffer.allocate(1);
        ByteBuffer excess = ByteBuffer.allocate(1);
        subscriber.onNext(List.of(last, excess));
        assertTrue(subscription.cancelled);
        assertEquals(0, excess.position(), "The byte beyond the cap must never enter body storage");
        long demandAtOverflow = subscription.demand;
        ByteBuffer late = ByteBuffer.allocate(32);
        subscriber.onNext(List.of(late));
        subscriber.onComplete();
        assertEquals(0, late.position());
        assertEquals(demandAtOverflow, subscription.demand, "No more transport demand after rejection");
        assertUnavailable(subscriber);
    }

    @Test void errorBodyIsDiscardedWithoutCopyingEvenAtCap() throws Exception {
        var subscriber = subscriber(503);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        ByteBuffer error = ByteBuffer.allocate(1_048_576);
        subscriber.onNext(List.of(error));
        subscriber.onComplete();
        assertEquals(0, error.position(), "Non-200 response text must not be copied");
        assertArrayEquals(new byte[0], subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test void transportErrorCannotPublishPartialJsonOrExposeResponseText() {
        var subscriber = subscriber(200);
        subscriber.onSubscribe(new Subscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {'{'})));
        subscriber.onError(new IOException("synthetic-response-text-never-expose"));
        subscriber.onComplete();
        ExecutionException failure = assertUnavailable(subscriber);
        assertNull(failure.getCause().getCause());
        assertFalse(failure.toString().contains("synthetic-response-text-never-expose"));
        assertFalse(failure.getCause().toString().contains("synthetic-response-text-never-expose"));
    }

    private static ExecutionException assertUnavailable(HttpResponse.BodySubscriber<byte[]> subscriber) {
        return assertThrows(ExecutionException.class,
                () -> subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    private static HttpResponse.BodySubscriber<byte[]> subscriber(int status) {
        return new BoundedResponseBodyHandler().apply(new HttpResponse.ResponseInfo() {
            @Override public int statusCode() { return status; }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        });
    }

    private static final class Subscription implements Flow.Subscription {
        private boolean cancelled;
        private long demand;
        @Override public void request(long count) { demand += count; }
        @Override public void cancel() { cancelled = true; }
    }
}
