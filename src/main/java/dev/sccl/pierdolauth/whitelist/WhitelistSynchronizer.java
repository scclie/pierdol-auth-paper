package dev.sccl.pierdolauth.whitelist;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistResult;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class WhitelistSynchronizer implements AutoCloseable {
    private final GameAuthClient client;
    private final PaperWhitelist whitelist;
    private final Consumer<Runnable> mainThread;
    private final long intervalNanos;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    // The monitor also serializes close against a main-thread application.
    private OptionalLong cursor = OptionalLong.empty();
    private CompletableFuture<WhitelistResult> pending;
    private boolean started;
    private boolean closed;

    public WhitelistSynchronizer(GameAuthClient client, PaperWhitelist whitelist,
            Duration interval, Consumer<Runnable> mainThread) {
        this.client = Objects.requireNonNull(client);
        this.whitelist = Objects.requireNonNull(whitelist);
        this.mainThread = Objects.requireNonNull(mainThread);
        intervalNanos = interval.toNanos();
        if (intervalNanos <= 0) throw new IllegalArgumentException("Interval must be positive");
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "game-auth-whitelist");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (closed || started) return;
        started = true;
        timer.scheduleWithFixedDelay(this::syncNow, 0, intervalNanos, TimeUnit.NANOSECONDS);
    }

    public synchronized void syncNow() {
        if (closed || !inFlight.compareAndSet(false, true)) return;
        try {
            pending = client.fetchWhitelist(cursor).toCompletableFuture();
            pending.whenComplete(this::received);
        } catch (RuntimeException ignored) {
            release();
        }
    }

    private synchronized void received(WhitelistResult result, Throwable failure) {
        if (closed) return;
        if (failure != null) {
            release();
        } else if (result instanceof WhitelistResult.Unchanged unchanged) {
            cursor = OptionalLong.of(unchanged.version());
            release();
        } else if (result instanceof WhitelistResult.Snapshot snapshot) {
            try {
                mainThread.accept(() -> apply(snapshot));
            } catch (RuntimeException ignored) {
                release();
            }
        } else {
            release();
        }
    }

    private synchronized void apply(WhitelistResult.Snapshot snapshot) {
        if (closed) return;
        try {
            whitelist.replaceWith(snapshot.identities());
            cursor = OptionalLong.of(snapshot.version());
        } catch (RuntimeException ignored) {
            // Retry from the last successfully applied version; never log API data.
        } finally {
            release();
        }
    }

    private void release() {
        pending = null;
        inFlight.set(false);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        timer.shutdownNow();
        if (pending != null) pending.cancel(false);
        release();
    }
}
