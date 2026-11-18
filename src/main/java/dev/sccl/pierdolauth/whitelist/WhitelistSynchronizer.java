package dev.sccl.pierdolauth.whitelist;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistResult;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class WhitelistSynchronizer implements AutoCloseable {
    @FunctionalInterface
    public interface Projection {
        boolean apply(WhitelistRegistry.Snapshot snapshot);
    }

    /** Only optional dependency projections may degrade after deferred API linkage failure. */
    public interface OptionalProjection extends Projection {
        // main-thread invalidation must finish before disabled/no-op publication is allowed.
        void disable();
    }

    private static final Projection DISABLED_PROJECTION = snapshot -> true;

    public static Projection disabledProjection() {
        return DISABLED_PROJECTION;
    }

    private final GameAuthClient client;
    private final PaperWhitelist whitelist;
    private final Consumer<Runnable> mainThread;
    private final Consumer<WhitelistResult.Snapshot> publisher;
    private Projection projection;
    private final long intervalNanos;
    private final ScheduledExecutorService timer;
    private final ExecutorService projectionExecutor;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    // The monitor also serializes close against a main-thread application.
    private OptionalLong cursor = OptionalLong.empty();
    private CompletableFuture<WhitelistResult> pending;
    private boolean started;
    private boolean closed;

    public WhitelistSynchronizer(GameAuthClient client, PaperWhitelist whitelist,
            Duration interval, Consumer<Runnable> mainThread) {
        this(client, whitelist, interval, mainThread, snapshot -> {}, DISABLED_PROJECTION);
    }

    public WhitelistSynchronizer(GameAuthClient client, PaperWhitelist whitelist,
            Duration interval, Consumer<Runnable> mainThread,
            Consumer<WhitelistResult.Snapshot> publisher) {
        this(client, whitelist, interval, mainThread, publisher, DISABLED_PROJECTION);
    }

    public WhitelistSynchronizer(GameAuthClient client, PaperWhitelist whitelist,
            Duration interval, Consumer<Runnable> mainThread,
            Consumer<WhitelistResult.Snapshot> publisher, Projection projection) {
        this.client = Objects.requireNonNull(client);
        this.whitelist = Objects.requireNonNull(whitelist);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.publisher = Objects.requireNonNull(publisher);
        this.projection = Objects.requireNonNull(projection);
        intervalNanos = interval.toNanos();
        if (intervalNanos <= 0) throw new IllegalArgumentException("Interval must be positive");
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "game-auth-whitelist");
            thread.setDaemon(true);
            return thread;
        });
        projectionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fastlogin-projection");
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

    private synchronized void apply(WhitelistResult.Snapshot backendSnapshot) {
        if (closed) return;
        WhitelistRegistry.Snapshot registrySnapshot;
        try {
            registrySnapshot = WhitelistRegistry.prepare(backendSnapshot.identities());
            whitelist.replaceWith(backendSnapshot.identities());
            if (projection == DISABLED_PROJECTION) {
                complete(backendSnapshot, registrySnapshot, true);
            } else {
                projectionExecutor.execute(() -> project(backendSnapshot, registrySnapshot));
            }
        } catch (RuntimeException ignored) {
            // retry from the last successfully applied version
            release();
        }
    }

    private void project(WhitelistResult.Snapshot backendSnapshot,
            WhitelistRegistry.Snapshot registrySnapshot) {
        boolean projected;
        try {
            projected = projection.apply(registrySnapshot);
        } catch (LinkageError failure) {
            // scoped to the optional invocation
            if (!(projection instanceof OptionalProjection optional)) throw failure;
            try {
                mainThread.accept(() -> disableAndComplete(backendSnapshot, registrySnapshot, optional));
            } catch (RuntimeException ignored) {
                release();
            }
            return;
        } catch (RuntimeException ignored) {
            projected = false;
        }
        boolean applied = projected;
        try {
            mainThread.accept(() -> complete(backendSnapshot, registrySnapshot, applied));
        } catch (RuntimeException ignored) {
            release();
        }
    }

    private synchronized void disableAndComplete(WhitelistResult.Snapshot backendSnapshot,
            WhitelistRegistry.Snapshot registrySnapshot, OptionalProjection optional) {
        if (closed) return;
        try {
            optional.disable();
            projection = DISABLED_PROJECTION;
            complete(backendSnapshot, registrySnapshot, true);
        } catch (RuntimeException ignored) {
            release();
        }
    }

    private synchronized void complete(WhitelistResult.Snapshot backendSnapshot,
            WhitelistRegistry.Snapshot registrySnapshot, boolean projected) {
        try {
            if (!closed && inFlight.get() && projected) {
                publisher.accept(backendSnapshot);
                cursor = OptionalLong.of(backendSnapshot.version());
            }
        } catch (RuntimeException ignored) {
            // Retry from the last successfully applied version; never log API data.
        } finally {
            release();
        }
    }

    private synchronized void release() {
        pending = null;
        inFlight.set(false);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        timer.shutdownNow();
        projectionExecutor.shutdownNow();
        if (pending != null) pending.cancel(false);
        release();
    }
}
