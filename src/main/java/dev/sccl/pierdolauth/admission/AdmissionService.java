package dev.sccl.pierdolauth.admission;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.api.GameAuthModels.ClientType;
import dev.sccl.pierdolauth.api.GameAuthModels.CrackedLoginResult;
import dev.sccl.pierdolauth.api.GameAuthModels.SessionCheckResult;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * runs on the main thread; callbacks and timers return to it
 * dispatcher must enqueue without blocking until close
 * scheduler supports cancellation; shared dependencies are not owned here
 */
public final class AdmissionService implements AutoCloseable {
    private static final String UNAVAILABLE = "Authentication service is unavailable. Please try again later.";
    public interface Cancellation { void cancel(); }
    public interface Scheduler { Cancellation schedule(Duration delay, Runnable task); }

    /** unique handle for each connection, retained by the adapter. */
    public static final class ConnectionToken {
        private final UUID uuid;
        private ConnectionToken(UUID uuid) { this.uuid = uuid; }
    }

    private final WhitelistRegistry registry;
    private final BooleanSupplier bridgeReady;
    private final AdmissionActions actions;
    private final GameAuthClient client;
    private final Duration heartbeat;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Consumer<Runnable> mainThread;
    private final Map<UUID, Entry> active = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Resume> resumes = new LinkedHashMap<>();
    private final int resumeCapacity;
    private Cancellation cleanup;
    private boolean closed;
    private long generation;

    private enum State { FROZEN, LOGIN_PENDING, AUTHENTICATED, RESUME_PENDING, LICENSED_PENDING }
    private static final class Entry {
        final ConnectionIdentity connection;
        final long generation;
        final ConnectionToken token;
        final ClientType originalType;
        State state = State.FROZEN;
        String lease;
        long expiry;
        Cancellation timer;
        Resume candidate;
        Entry(ConnectionIdentity connection, long generation, ClientType originalType) {
            this.connection = connection;
            this.generation = generation;
            this.token = new ConnectionToken(connection.uuid());
            this.originalType = originalType;
        }
    }
    private static final class Resume {
        final byte[] address;
        final long generation;
        String lease;
        long expiry;
        boolean renewalNeeded = true;
        Resume(byte[] address, long generation, String lease, long expiry) {
            this.address = address.clone();
            this.generation = generation;
            this.lease = lease;
            this.expiry = expiry;
        }
    }
    private enum Check { HEARTBEAT, RESUME, FINAL }
    private static final class Pending {}

    public AdmissionService(GameAuthClient client, WhitelistRegistry registry, Duration heartbeat,
            Clock clock, Scheduler scheduler, Consumer<Runnable> mainThread, AdmissionActions actions,
            int resumeCapacity) {
        this(client, registry, heartbeat, clock, scheduler, mainThread, actions, resumeCapacity, () -> false);
    }

    public AdmissionService(GameAuthClient client, WhitelistRegistry registry, Duration heartbeat,
            Clock clock, Scheduler scheduler, Consumer<Runnable> mainThread, AdmissionActions actions,
            int resumeCapacity, BooleanSupplier bridgeReady) {
        this.bridgeReady = Objects.requireNonNull(bridgeReady);
        this.registry = Objects.requireNonNull(registry);
        this.actions = Objects.requireNonNull(actions);
        this.client = Objects.requireNonNull(client);
        this.heartbeat = Objects.requireNonNull(heartbeat);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.mainThread = Objects.requireNonNull(mainThread);
        if (heartbeat.isNegative() || heartbeat.isZero()) throw new IllegalArgumentException("Interval must be positive");
        if (resumeCapacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.resumeCapacity = resumeCapacity;
        scheduleCleanup();
    }

    public synchronized ConnectionToken join(ConnectionIdentity connection) {
        if (closed) return new ConnectionToken(connection.uuid());
        sweep();
        UUID uuid = connection.uuid();
        Entry old = active.remove(uuid);
        if (old != null && old.timer != null) old.timer.cancel();
        Resume candidate = resumes.remove(uuid);
        WhitelistRegistry.Snapshot snapshot = registry.snapshot();
        if (!snapshot.ready() || !snapshot.clientTypes().containsKey(uuid)) {
            actions.kick(uuid, UNAVAILABLE);
        } else if (snapshot.clientTypes().get(uuid) == ClientType.LICENSED) {
            if (!bridgeReady.getAsBoolean()) {
                actions.kick(uuid, UNAVAILABLE);
            } else {
                Entry entry = new Entry(connection, ++generation, ClientType.LICENSED);
                entry.state = State.LICENSED_PENDING;
                active.put(uuid, entry);
                entry.timer = scheduler.schedule(Duration.ofSeconds(10),
                        () -> dispatch(() -> licensedExpired(entry.token)));
                return entry.token;
            }
        } else {
            Entry entry = new Entry(connection, ++generation, ClientType.CRACKED);
            active.put(uuid, entry);
            if (candidate != null && Arrays.equals(candidate.address, connection.address())) {
                entry.state = State.RESUME_PENDING;
                entry.candidate = candidate;
                drive(uuid);
            } else {
                actions.prompt(uuid);
            }
            return entry.token;
        }
        return new ConnectionToken(uuid);
    }

    public synchronized boolean frozen(ConnectionToken token) {
        if (token == null) return false;
        Entry entry = active.get(token.uuid);
        return entry != null && entry.token == token && entry.state != State.AUTHENTICATED;
    }

    private synchronized void licensedExpired(ConnectionToken token) {
        Entry entry = active.get(token.uuid);
        if (closed || entry == null || entry.token != token || entry.state != State.LICENSED_PENDING) return;
        active.remove(token.uuid);
        if (entry.timer != null) entry.timer.cancel();
        actions.kick(token.uuid, UNAVAILABLE);
    }

    public synchronized boolean premiumVerified(ConnectionToken token, UUID uuid, String nickname) {
        if (closed || token == null || uuid == null || nickname == null || !bridgeReady.getAsBoolean()) return false;
        Entry entry = active.get(token.uuid);
        if (entry == null || entry.token != token || entry.originalType != ClientType.LICENSED
                || entry.state != State.LICENSED_PENDING || !entry.connection.uuid().equals(uuid)) return false;
        var snapshot = registry.snapshot();
        var identity = snapshot.identity(uuid);
        if (!snapshot.ready() || identity == null || identity.clientType() != ClientType.LICENSED
                || !identity.nickname().toLowerCase(Locale.ROOT).equals(nickname.toLowerCase(Locale.ROOT))
                || !identity.nickname().toLowerCase(Locale.ROOT).equals(entry.connection.nickname().toLowerCase(Locale.ROOT))) return false;
        entry.state = State.AUTHENTICATED;
        if (entry.timer != null) entry.timer.cancel();
        entry.timer = null;
        actions.release(uuid);
        return true;
    }

    public synchronized void revokeLicensed(WhitelistRegistry.Snapshot snapshot) {
        if (closed || snapshot != registry.snapshot() || !snapshot.ready()) return;
        // snapshot iteration and remove-before-kick tolerate synchronous Paper quit callbacks.
        for (Entry entry : List.copyOf(active.values())) {
            UUID uuid = entry.connection.uuid();
            if (entry.originalType == ClientType.LICENSED
                    && snapshot.clientTypes().get(uuid) != ClientType.LICENSED
                    && active.remove(uuid, entry)) {
                if (entry.timer != null) entry.timer.cancel();
                actions.kick(uuid, "Your authentication session is no longer valid. Please reconnect.");
            }
        }
    }

    /** Takes ownership of input, including when ignored. */
    public synchronized void command(ConnectionToken token, char[] input) {
        Objects.requireNonNull(input);
        try {
            if (token == null) return;
            UUID uuid = token.uuid;
            Entry entry = active.get(uuid);
            if (closed || entry == null || entry.token != token || entry.state != State.FROZEN || pending.containsKey(uuid)) return;
            if (input.length == 0) { actions.prompt(uuid); return; }
            char[] copy = input.clone();
            Arrays.fill(input, '\0');
            Pending request = new Pending();
            pending.put(uuid, request);
            entry.state = State.LOGIN_PENDING;
            long expected = entry.generation;
            CompletionStage<CrackedLoginResult> started;
            try {
                started = client.crackedLogin(entry.connection.nickname(), copy);
            } catch (RuntimeException ignored) {
                // Invocation transfers ownership even on failure; cleanup belongs to the client.
                dispatch(() -> loginCompleted(uuid, expected, request, null));
                return;
            }
            // The client is the sole owner from invocation; never retain or clear its input.
            try {
                started.whenComplete((result, failure) -> {
                    CrackedLoginResult received = failure == null ? result : null;
                    dispatch(() -> loginCompleted(uuid, expected, request, received));
                });
            } catch (RuntimeException ignored) {
                dispatch(() -> loginCompleted(uuid, expected, request, null));
            }
        } finally {
            Arrays.fill(input, '\0');
        }
    }

    private synchronized void loginCompleted(UUID uuid, long expected, Pending request,
            CrackedLoginResult result) {
        if (closed || !pending.remove(uuid, request)) return;
        Entry entry = active.get(uuid);
        if (entry == null || entry.generation != expected || entry.state != State.LOGIN_PENDING) {
            drive(uuid);
            return;
        }
        if (result instanceof CrackedLoginResult.Allowed allowed
                && uuid.equals(allowed.uuid()) && allowed.lease() != null && !allowed.lease().isBlank()
                && allowed.expiresAtEpochSeconds() > clock.instant().getEpochSecond()) {
            entry.lease = allowed.lease();
            entry.expiry = allowed.expiresAtEpochSeconds();
            entry.state = State.AUTHENTICATED;
            actions.release(uuid);
            scheduleHeartbeat(uuid, entry);
        } else {
            entry.state = State.FROZEN;
            actions.message(uuid, result instanceof CrackedLoginResult.Denied
                    ? "Invalid password." : UNAVAILABLE);
        }
    }

    private void scheduleHeartbeat(UUID uuid, Entry entry) {
        long expected = entry.generation;
        entry.timer = scheduler.schedule(heartbeat,
                () -> dispatch(() -> heartbeat(uuid, expected)));
    }

    private synchronized void heartbeat(UUID uuid, long expected) {
        if (closed) return;
        Entry entry = active.get(uuid);
        if (entry == null || entry.generation != expected || entry.state != State.AUTHENTICATED) return;
        entry.timer = null;
        if (pending.containsKey(uuid)) return;
        check(uuid, expected, entry.lease, Check.HEARTBEAT);
    }

    private void heartbeatReceived(UUID uuid, long expected, SessionCheckResult result) {
        Entry entry = active.get(uuid);
        if (entry == null || entry.generation != expected || entry.state != State.AUTHENTICATED) return;
        if (result instanceof SessionCheckResult.Disconnected
                || result instanceof SessionCheckResult.Continued continued && !uuid.equals(continued.uuid())) {
            active.remove(uuid);
            resumes.remove(uuid);
            actions.kick(uuid, "Your authentication session is no longer valid. Please reconnect.");
            return;
        }
        if (valid(uuid, result)) {
            SessionCheckResult.Continued continued = (SessionCheckResult.Continued) result;
            entry.lease = continued.lease();
            entry.expiry = continued.expiresAtEpochSeconds();
        }
        scheduleHeartbeat(uuid, entry);
    }

    public synchronized void quit(ConnectionToken token) {
        if (closed || token == null) return;
        UUID uuid = token.uuid;
        Entry entry = active.get(uuid);
        if (entry == null || entry.token != token) return;
        active.remove(uuid);
        if (entry.timer != null) entry.timer.cancel();
        if (entry.state != State.AUTHENTICATED || entry.originalType != ClientType.CRACKED) return;
        sweep();
        resumes.remove(uuid);
        while (resumes.size() >= resumeCapacity) resumes.remove(resumes.keySet().iterator().next());
        resumes.put(uuid, new Resume(entry.connection.address(), ++generation, entry.lease, entry.expiry));
        drive(uuid);
    }

    /** wailed/kicked connections do not earn a cracked resume or final renewal. */
    public synchronized void discard(ConnectionToken token) {
        if (token == null) return;
        Entry entry = active.get(token.uuid);
        if (entry != null && entry.token == token) {
            active.remove(token.uuid);
            if (entry.timer != null) entry.timer.cancel();
        }
    }

    private void drive(UUID uuid) {
        if (closed || pending.containsKey(uuid)) return;
        Entry entry = active.get(uuid);
        if (entry != null && entry.state == State.RESUME_PENDING) {
            if (entry.candidate.expiry <= clock.instant().getEpochSecond()) {
                fallback(uuid, entry);
            } else {
                check(uuid, entry.generation, entry.candidate.lease, Check.RESUME);
            }
            return;
        }
        Resume resume = resumes.get(uuid);
        if (resume != null && resume.renewalNeeded) {
            resume.renewalNeeded = false;
            check(uuid, resume.generation, resume.lease, Check.FINAL);
        }
    }

    private void check(UUID uuid, long expected, String lease, Check kind) {
        Pending request = new Pending();
        pending.put(uuid, request);
        try {
            client.checkSession(lease).whenComplete((result, failure) -> {
                SessionCheckResult received = failure == null ? result : null;
                dispatch(() -> checkCompleted(uuid, expected, request, kind, received));
            });
        } catch (RuntimeException ignored) {
            dispatch(() -> checkCompleted(uuid, expected, request, kind, null));
        }
    }

    private synchronized void checkCompleted(UUID uuid, long expected, Pending request, Check kind,
            SessionCheckResult result) {
        if (closed || !pending.remove(uuid, request)) return;
        switch (kind) {
            case HEARTBEAT -> heartbeatReceived(uuid, expected, result);
            case RESUME -> {
                Entry entry = active.get(uuid);
                if (entry != null && entry.generation == expected && entry.state == State.RESUME_PENDING) {
                    if (valid(uuid, result)) {
                        SessionCheckResult.Continued continued = (SessionCheckResult.Continued) result;
                        entry.candidate = null;
                        entry.lease = continued.lease();
                        entry.expiry = continued.expiresAtEpochSeconds();
                        entry.state = State.AUTHENTICATED;
                        actions.release(uuid);
                        scheduleHeartbeat(uuid, entry);
                    } else {
                        fallback(uuid, entry);
                    }
                }
            }
            case FINAL -> {
                Resume resume = resumes.get(uuid);
                if (resume != null && resume.generation == expected) {
                    if (valid(uuid, result)) {
                        SessionCheckResult.Continued continued = (SessionCheckResult.Continued) result;
                        resume.lease = continued.lease();
                        resume.expiry = continued.expiresAtEpochSeconds();
                    } else if (result instanceof SessionCheckResult.Disconnected
                            || result instanceof SessionCheckResult.Continued continued && !uuid.equals(continued.uuid())) {
                        resumes.remove(uuid);
                    }
                }
            }
        }
        drive(uuid);
    }

    private boolean valid(UUID uuid, SessionCheckResult result) {
        return result instanceof SessionCheckResult.Continued continued && uuid.equals(continued.uuid())
                && continued.lease() != null && !continued.lease().isBlank()
                && continued.expiresAtEpochSeconds() > clock.instant().getEpochSecond();
    }

    private void fallback(UUID uuid, Entry entry) {
        entry.candidate = null;
        entry.state = State.FROZEN;
        actions.prompt(uuid);
    }

    private void sweep() {
        long now = clock.instant().getEpochSecond();
        resumes.values().removeIf(resume -> resume.expiry <= now);
    }

    private void scheduleCleanup() {
        cleanup = scheduler.schedule(Duration.ofMinutes(5), () -> dispatch(this::cleanup));
    }

    private synchronized void cleanup() {
        if (closed) return;
        sweep();
        scheduleCleanup();
    }

    private synchronized void dispatch(Runnable task) {
        if (!closed) mainThread.accept(task);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (cleanup != null) cleanup.cancel();
        cleanup = null;
        for (Entry entry : active.values()) if (entry.timer != null) entry.timer.cancel();
        pending.clear();
        active.clear();
        resumes.clear();
    }
}
