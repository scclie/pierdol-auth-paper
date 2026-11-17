package dev.sccl.pierdolauth;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.config.ConfigLoader;
import dev.sccl.pierdolauth.config.ConfigurationException;
import dev.sccl.pierdolauth.config.PluginConfig;
import dev.sccl.pierdolauth.whitelist.PaperWhitelist;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLifecycleTest {
    @TempDir Path directory;
    private boolean failureGuardRegistered;

    @Test void admissionShutdownPrecedesSynchronizerAndClientAndIgnoresLateCallbacks() throws Exception {
        var order = new java.util.ArrayList<String>();
        var queued = new java.util.ArrayList<Runnable>();
        var shared = new java.util.concurrent.atomic.AtomicReference<WhitelistRegistry>();
        var admission = new java.util.concurrent.atomic.AtomicReference<dev.sccl.pierdolauth.admission.AdmissionService>();
        try (PluginLifecycle lifecycle = new PluginLifecycle(this::validConfig, config -> client,
                (http, config, registry) -> {
                    shared.set(registry);
                    return new WhitelistSynchronizer(http, whitelist, config.whitelistSyncInterval(), mainThread::add);
                }, (http, config, registry) -> {
                    assertSame(shared.get(), registry);
                    var service = admission(http, registry, queued, () -> order.add("admission"));
                    admission.set(service);
                    return service;
                }, (service, registry) -> new PluginLifecycle.ListenerRegistration() {
                    public void register() { assertSame(admission.get(), service); assertSame(shared.get(), registry); }
                    public void close() { order.add("listener"); }
                }, failureGuard(() -> failureGuardRegistered = true))) {
            assertTrue(lifecycle.enable());
            var pending = client.next();
            client.onClose = () -> {
                assertTrue(pending.isCancelled());
                assertEquals(java.util.List.of("listener", "admission"), order);
                order.add("client");
            };
            lifecycle.close();
            assertEquals(java.util.List.of("listener", "admission", "client"), order);
            queued.forEach(Runnable::run);
            assertEquals(1, queued.size(), "late cleanup must not schedule again");
        }
    }

    @Test void admissionAndListenerConstructionFailuresReleaseAcquiredResources() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            int failureStage = stage;
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var listenerClosed = new java.util.concurrent.atomic.AtomicBoolean();
            client.closed = false;
            failureGuardRegistered = false;
            try (PluginLifecycle lifecycle = new PluginLifecycle(this::validConfig, config -> client,
                    (http, config, registry) -> new WhitelistSynchronizer(http, whitelist,
                            config.whitelistSyncInterval(), mainThread::add),
                    (http, config, registry) -> {
                        if (failureStage == 0) throw new IllegalStateException();
                        return admission(http, registry, new java.util.ArrayList<>(), () -> cancelled.set(true));
                    }, (service, registry) -> {
                        if (failureStage == 1) throw new IllegalStateException();
                        return new PluginLifecycle.ListenerRegistration() {
                            public void register() { throw new IllegalStateException(); }
                            public void close() { listenerClosed.set(true); }
                        };
                    }, failureGuard(() -> { assertTrue(client.closed); failureGuardRegistered = true; }))) {
                assertFalse(lifecycle.enable());
                assertTrue(failureGuardRegistered);
                assertNull(lifecycle.registry());
                if (stage > 0) assertTrue(cancelled.get());
                if (stage == 2) assertTrue(listenerClosed.get());
            }
        }
    }

    private dev.sccl.pierdolauth.admission.AdmissionService admission(GameAuthClient http,
            WhitelistRegistry registry, java.util.List<Runnable> queued, Runnable cancel) {
        return new dev.sccl.pierdolauth.admission.AdmissionService(http, registry, Duration.ofSeconds(60),
                java.time.Clock.systemUTC(), (delay, task) -> { queued.add(task); return cancel::run; },
                Runnable::run, new dev.sccl.pierdolauth.admission.AdmissionActions() {
                    public void prompt(UUID uuid) { fail("late action"); }
                    public void release(UUID uuid) { fail("late action"); }
                    public void message(UUID uuid, String message) { fail("late action"); }
                    public void kick(UUID uuid, String reason) { fail("late action"); }
                }, 10);
    }
    private final FakeClient client = new FakeClient();
    private final BlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
    private WhitelistRegistry registry;
    private final PaperWhitelist whitelist = new PaperWhitelist() {
        @Override public Set<UUID> currentUuids() {
            throw new AssertionError("enable/disable must not read Paper state");
        }
        @Override public void replaceWith(Collection<WhitelistIdentity> identities) {
            throw new AssertionError("queued snapshots must not apply after disable");
        }
    };

    @Test void invalidConfigRegistersGuardWithoutClientConstruction() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(
                () -> new ConfigLoader().load(directory.resolve("missing-config.yml")),
                config -> { throw new AssertionError("invalid config reached client construction"); },
                (http, config, sharedRegistry) -> { throw new AssertionError("invalid config reached synchronizer"); },
                failureGuard(() -> failureGuardRegistered = true))) {
            assertFalse(lifecycle.enable());
            assertTrue(failureGuardRegistered);
            assertNull(lifecycle.registry());
        }
    }

    @Test void validConfigSchedulesImmediateSynchronizationWithoutBlockingEnable() throws Exception {
        try (PluginLifecycle lifecycle = lifecycle()) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(lifecycle.enable()));
            CompletableFuture<WhitelistResult> pending = client.next();
            assertFalse(pending.isDone(), "enable must not wait for the HTTP response");
            assertFalse(failureGuardRegistered);
            assertNotNull(registry);
            assertSame(registry, lifecycle.registry());
            assertFalse(registry.snapshot().ready());
        }
    }

    @Test void disableCancelsFetchBeforeClosingClientAndClearsOwnership() throws Exception {
        try (PluginLifecycle lifecycle = lifecycle()) {
            assertTrue(lifecycle.enable());
            CompletableFuture<WhitelistResult> pending = client.next();
            client.onClose = () -> assertTrue(pending.isCancelled(),
                    "synchronizer must stop before client closes");
            lifecycle.close();
            assertNull(lifecycle.registry());
            assertTrue(client.closed);
            client.onClose = () -> fail("cleared resources must not close twice");
            lifecycle.close();
        }
    }

    @Test void disableSuppressesQueuedSnapshot() throws Exception {
        try (PluginLifecycle lifecycle = lifecycle()) {
            assertTrue(lifecycle.enable());
            client.next().complete(new WhitelistResult.Snapshot(4, java.util.List.of()));
            Runnable queued = mainThread.poll(1, TimeUnit.SECONDS);
            assertNotNull(queued);
            lifecycle.close();
            assertDoesNotThrow(queued::run);
            assertTrue(client.closed);
            assertFalse(registry.snapshot().ready());
        }
    }

    @Test void registryBecomesReadyOnlyWhenSnapshotIsApplied() throws Exception {
        PaperWhitelist emptyWhitelist = new PaperWhitelist() {
            @Override public Set<UUID> currentUuids() {
                throw new AssertionError("publication must not read Paper state");
            }
            @Override public void replaceWith(Collection<WhitelistIdentity> identities) {
                assertFalse(registry.snapshot().ready());
                assertTrue(identities.isEmpty());
            }
        };
        try (PluginLifecycle lifecycle = lifecycle(emptyWhitelist)) {
            assertNull(registry, "registry is created at enable time");
            assertNull(lifecycle.registry());
            assertTrue(lifecycle.enable());
            WhitelistRegistry shared = lifecycle.registry();
            assertSame(registry, shared, "read-side owner must expose the factory's exact instance");
            client.next().complete(new WhitelistResult.Snapshot(4, java.util.List.of()));
            assertFalse(registry.snapshot().ready());
            Runnable queued = mainThread.poll(1, TimeUnit.SECONDS);
            assertNotNull(queued);
            queued.run();
            assertTrue(registry.snapshot().ready());
            assertTrue(registry.snapshot().clientTypes().isEmpty());
            assertSame(shared, lifecycle.registry());
            assertTrue(shared.snapshot().ready(), "publication must reach the shared read-side instance");
        }
    }

    @Test void clientConstructionFailureRegistersGuardWithoutConstructingSynchronizer() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(this::validConfig,
                config -> { throw new ConfigurationException(); },
                (http, config, sharedRegistry) -> { throw new AssertionError("client construction failed"); },
                failureGuard(() -> failureGuardRegistered = true))) {
            assertFalse(lifecycle.enable());
            assertTrue(failureGuardRegistered);
            assertNull(lifecycle.registry());
        }
    }

    @Test void synchronizerConstructionFailureClosesClientBeforeRegisteringGuard() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(this::validConfig, config -> client,
                (http, config, sharedRegistry) -> {
                    registry = sharedRegistry;
                    assertNotNull(registry);
                    throw new IllegalStateException("synthetic setup failure");
                },
                failureGuard(() -> { assertTrue(client.closed); failureGuardRegistered = true; }))) {
            assertFalse(lifecycle.enable());
            assertTrue(failureGuardRegistered);
            assertTrue(client.closed);
            assertNotNull(registry, "failure occurs after registry construction");
            assertNull(lifecycle.registry(), "failed startup must release registry ownership");
        }
    }

    @Test void runtimeConfigFailureRegistersGuardWithoutOpeningResources() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(
                () -> { throw new IllegalStateException("synthetic saveDefaultConfig failure"); },
                config -> { throw new AssertionError("failed config must not open a client"); },
                (http, config, sharedRegistry) -> { throw new AssertionError("failed config must not schedule"); },
                failureGuard(() -> failureGuardRegistered = true))) {
            assertFalse(lifecycle.enable());
            assertTrue(failureGuardRegistered);
        }
    }

    @Test void reenableCreatesFreshUnreadyRegistry() throws Exception {
        try (PluginLifecycle lifecycle = lifecycle(new PaperWhitelist() {
            @Override public Set<UUID> currentUuids() { return Set.of(); }
            @Override public void replaceWith(Collection<WhitelistIdentity> identities) {}
        })) {
            assertTrue(lifecycle.enable());
            WhitelistRegistry first = lifecycle.registry();
            client.next().complete(new WhitelistResult.Snapshot(4, java.util.List.of()));
            Runnable queued = mainThread.poll(1, TimeUnit.SECONDS);
            assertNotNull(queued);
            queued.run();
            assertTrue(first.snapshot().ready());
            lifecycle.close();
            assertNull(lifecycle.registry());
            assertTrue(lifecycle.enable());
            assertNotSame(first, lifecycle.registry());
            assertSame(registry, lifecycle.registry());
            assertFalse(lifecycle.registry().snapshot().ready());
            client.next();
        }
    }

    private PluginLifecycle lifecycle() {
        return lifecycle(whitelist);
    }

    private PluginLifecycle lifecycle(PaperWhitelist paperWhitelist) {
        return new PluginLifecycle(this::validConfig, config -> client,
                (http, config, sharedRegistry) -> {
                    registry = sharedRegistry;
                    return new WhitelistSynchronizer(http, paperWhitelist,
                            config.whitelistSyncInterval(), mainThread::add,
                            snapshot -> sharedRegistry.publish(snapshot.identities()));
                }, failureGuard(() -> failureGuardRegistered = true));
    }

    private PluginConfig validConfig() {
        return new PluginConfig("synthetic-server", URI.create("http://example.invalid/internal/game-auth/v1"),
                directory.resolve("unused-synthetic-credential"), Duration.ofHours(1),
                Duration.ofSeconds(30), "login");
    }

    private PluginLifecycle.ListenerRegistration failureGuard(Runnable onRegister) {
        return new PluginLifecycle.ListenerRegistration() {
            public void register() { onRegister.run(); }
            public void close() {}
        };
    }

    static final class FakeClient implements GameAuthClient {
        @Override public CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] password) {
            if (password != null) java.util.Arrays.fill(password, '\0');
            return CompletableFuture.completedFuture(new CrackedLoginResult.Unavailable());
        }
        @Override public CompletionStage<SessionCheckResult> checkSession(String lease) {
            return CompletableFuture.completedFuture(new SessionCheckResult.Unavailable());
        }
        private final BlockingQueue<CompletableFuture<WhitelistResult>> requests = new LinkedBlockingQueue<>();
        private Runnable onClose = () -> {};
        private boolean closed;

        @Override public CompletionStage<PolicyResult> fetchPolicy(String nickname) {
            throw new AssertionError("lifecycle must not request player policy");
        }
        @Override public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) {
            assertEquals(OptionalLong.empty(), cursor);
            CompletableFuture<WhitelistResult> pending = new CompletableFuture<>();
            requests.add(pending);
            return pending;
        }
        CompletableFuture<WhitelistResult> next() throws Exception {
            CompletableFuture<WhitelistResult> pending = requests.poll(1, TimeUnit.SECONDS);
            assertNotNull(pending, "enable must schedule synchronization");
            return pending;
        }
        @Override public void close() { onClose.run(); closed = true; }
    }
}
