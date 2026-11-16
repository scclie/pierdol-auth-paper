package dev.sccl.pierdolauth;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.config.ConfigLoader;
import dev.sccl.pierdolauth.config.ConfigurationException;
import dev.sccl.pierdolauth.config.PluginConfig;
import dev.sccl.pierdolauth.whitelist.PaperWhitelist;
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
    private boolean disabled;
    private final FakeClient client = new FakeClient();
    private final BlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
    private final PaperWhitelist whitelist = new PaperWhitelist() {
        @Override public Set<UUID> currentUuids() {
            throw new AssertionError("enable/disable must not read Paper state");
        }
        @Override public void replaceWith(Collection<WhitelistIdentity> identities) {
            throw new AssertionError("queued snapshots must not apply after disable");
        }
    };

    @Test void invalidConfigDisablesBeforeClientConstruction() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(
                () -> new ConfigLoader().load(directory.resolve("missing-config.yml")),
                config -> { throw new AssertionError("invalid config reached client construction"); },
                (http, config) -> { throw new AssertionError("invalid config reached synchronizer"); },
                () -> disabled = true)) {
            assertFalse(lifecycle.enable());
            assertTrue(disabled);
        }
    }

    @Test void validConfigSchedulesImmediateSynchronizationWithoutBlockingEnable() throws Exception {
        try (PluginLifecycle lifecycle = lifecycle()) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(lifecycle.enable()));
            CompletableFuture<WhitelistResult> pending = client.next();
            assertFalse(pending.isDone(), "enable must not wait for the HTTP response");
            assertFalse(disabled);
        }
    }

    @Test void disableCancelsFetchBeforeClosingClientAndClearsOwnership() throws Exception {
        try (PluginLifecycle lifecycle = lifecycle()) {
            assertTrue(lifecycle.enable());
            CompletableFuture<WhitelistResult> pending = client.next();
            client.onClose = () -> assertTrue(pending.isCancelled(),
                    "synchronizer must stop before client closes");
            lifecycle.close();
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
        }
    }

    @Test void clientConstructionFailureDisablesWithoutConstructingSynchronizer() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(this::validConfig,
                config -> { throw new ConfigurationException(); },
                (http, config) -> { throw new AssertionError("client construction failed"); },
                () -> disabled = true)) {
            assertFalse(lifecycle.enable());
            assertTrue(disabled);
        }
    }

    @Test void synchronizerConstructionFailureClosesClientBeforeDisabling() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(this::validConfig, config -> client,
                (http, config) -> { throw new IllegalStateException("synthetic setup failure"); },
                () -> { assertTrue(client.closed); disabled = true; })) {
            assertFalse(lifecycle.enable());
            assertTrue(disabled);
            assertTrue(client.closed);
        }
    }

    @Test void runtimeConfigFailureDisablesBeforeOpeningResources() {
        try (PluginLifecycle lifecycle = new PluginLifecycle(
                () -> { throw new IllegalStateException("synthetic saveDefaultConfig failure"); },
                config -> { throw new AssertionError("failed config must not open a client"); },
                (http, config) -> { throw new AssertionError("failed config must not schedule"); },
                () -> disabled = true)) {
            assertFalse(lifecycle.enable());
            assertTrue(disabled);
        }
    }

    private PluginLifecycle lifecycle() {
        return new PluginLifecycle(this::validConfig, config -> client,
                (http, config) -> new WhitelistSynchronizer(http, whitelist,
                        config.whitelistSyncInterval(), mainThread::add), () -> disabled = true);
    }

    private PluginConfig validConfig() {
        return new PluginConfig("synthetic-server", URI.create("http://example.invalid/internal/game-auth/v1"),
                directory.resolve("unused-synthetic-credential"), Duration.ofHours(1),
                Duration.ofSeconds(30), "login");
    }

    private static final class FakeClient implements GameAuthClient {
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
