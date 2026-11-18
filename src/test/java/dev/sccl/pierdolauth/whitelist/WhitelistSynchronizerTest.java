package dev.sccl.pierdolauth.whitelist;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.licensed.FastLoginProjection;
import com.github.games647.fastlogin.core.storage.AuthStorage;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WhitelistSynchronizerTest {
    private static final UUID STALE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CRACKED = UUID.fromString("a01e3843-e521-3998-958a-f459800e4d11");
    private static final UUID LICENSED = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final List<WhitelistIdentity> IDENTITIES = List.of(
            new WhitelistIdentity("Player", ClientType.CRACKED, CRACKED),
            new WhitelistIdentity("Licensed", ClientType.LICENSED, LICENSED));
    private final FakeClient client = new FakeClient();
    private final RecordingWhitelist whitelist = new RecordingWhitelist();
    private final BlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
    private final WhitelistRegistry registry = new WhitelistRegistry();
    private int publications;
    private boolean failPublication;
    private final WhitelistSynchronizer sync = new WhitelistSynchronizer(
            client, whitelist, Duration.ofHours(1), mainThread::add, snapshot -> {
                assertTrue(whitelist.onMainThread);
                assertEquals(Thread.currentThread(), whitelist.mutationThread);
                assertEquals(snapshot.identities(), whitelist.lastIdentities);
                publications++;
                if (failPublication) throw new IllegalStateException("synthetic publication failure");
                registry.publish(snapshot.identities());
            });

    @AfterEach void close() { sync.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"method", "abstract", "class"})
    void deferredLinkageCompletesOnlyAsDisabledAndAllowsFurtherFetches(String failure) throws Exception {
        var storage = new ProjectionStorage(Thread.currentThread());
        storage.linkage = switch (failure) {
            case "method" -> new NoSuchMethodError("deferred test failure");
            case "abstract" -> new AbstractMethodError("deferred test failure");
            default -> new NoClassDefFoundError("deferred test failure");
        };
        try (var enabled = enabledSynchronizer(storage)) {
            var before = registry.snapshot();
            enabled.syncNow();
            client.next().result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
            applyNext();
            Runnable completion = mainThread.poll(2, TimeUnit.SECONDS);
            assertNotNull(completion, "optional linkage must not strand the in-flight worker");
            assertSame(before, registry.snapshot());
            assertEquals(0, publications, "failed enabled candidate must not publish/revoke");
            enabled.syncNow();
            assertTrue(client.requests.isEmpty());
            runMain(completion);
            assertTrue(registry.snapshot().ready(), "disabled/no-op completion preserves cracked operation");
            assertEquals(ClientType.CRACKED, registry.snapshot().clientTypes().get(CRACKED));
            assertEquals(1, publications);
            assertEquals(1, storage.linkageCalls);
            enabled.syncNow();
            Request later = client.next();
            assertEquals(OptionalLong.of(42), later.cursor());
            later.result().complete(new WhitelistResult.Snapshot(43, IDENTITIES));
            applyNext();
            assertEquals(2, publications, "future snapshots use the successful disabled/no-op projection");
            assertEquals(1, storage.linkageCalls, "unavailable optional API must never be retried");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realProjectionFailureRetainsPublicationAndCursorThenRetriesOnce(boolean previous) throws Exception {
        var storage = new ProjectionStorage(Thread.currentThread());
        try (var enabled = enabledSynchronizer(storage)) {
            if (previous) {
                enabled.syncNow();
                client.next().result().complete(new WhitelistResult.Snapshot(12, List.of(IDENTITIES.getFirst())));
                applyNext();
                applyNext();
            }
            var before = registry.snapshot();
            int count = publications;
            storage.drop = true;
            enabled.syncNow();
            client.next().result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
            applyNext(); // paper mutation submits real projection to its worker
            Runnable completion = mainThread.poll(2, TimeUnit.SECONDS);
            assertNotNull(completion);
            assertSame(before, registry.snapshot());
            assertEquals(count, publications);
            enabled.syncNow();
            assertTrue(client.requests.isEmpty(), "queued projection completion remains in flight");
            runMain(completion);
            assertSame(before, registry.snapshot());
            assertEquals(count, publications, "failure must not notify publisher/revocation consumer");
            enabled.syncNow();
            Request retry = client.next();
            assertEquals(previous ? OptionalLong.of(12) : OptionalLong.empty(), retry.cursor());
            storage.drop = false;
            retry.result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
            applyNext();
            assertEquals(count, publications);
            applyNext();
            assertEquals(count + 1, publications);
            assertEquals(IDENTITIES.size(), registry.snapshot().identities().size());
            enabled.syncNow();
            assertEquals(OptionalLong.of(42), client.next().cursor());
        }
    }

    @Test void optionalRuntimeFailureDoesNotDisableOrPublishAndRetriesEnabledStorage() throws Exception {
        var storage = new ProjectionStorage(Thread.currentThread());
        storage.runtime = true;
        try (var enabled = enabledSynchronizer(storage)) {
            var before = registry.snapshot();
            enabled.syncNow();
            client.next().result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
            applyNext();
            applyNext();
            assertSame(before, registry.snapshot());
            assertEquals(0, publications);
            assertFalse(storage.disabled);
            enabled.syncNow();
            var retry = client.next();
            assertEquals(OptionalLong.empty(), retry.cursor());
            storage.runtime = false;
            retry.result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
            applyNext();
            applyNext();
            assertEquals(1, publications);
            assertNotNull(storage.persisted, "retry must confirm enabled storage, not bypass it");
            assertFalse(storage.disabled);
        }
    }

    @Test void closeSuppressesRealProjectionQueuedCompletion() throws Exception {
        var storage = new ProjectionStorage(Thread.currentThread());
        try (var enabled = enabledSynchronizer(storage)) {
            enabled.syncNow();
            client.next().result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
            applyNext();
            Runnable completion = mainThread.poll(2, TimeUnit.SECONDS);
            assertNotNull(completion, "worker must finish before close");
            assertNotNull(storage.persisted);
            enabled.close();
            runMain(completion);
            assertFalse(registry.snapshot().ready());
            assertEquals(0, publications);
            enabled.syncNow();
            assertTrue(client.requests.isEmpty());
        }
    }

    private WhitelistSynchronizer enabledSynchronizer(ProjectionStorage storage) {
        return new WhitelistSynchronizer(client, whitelist, Duration.ofHours(1), mainThread::add,
                snapshot -> {
                    assertTrue(whitelist.onMainThread);
                    assertEquals(storage.main, Thread.currentThread());
                    assertEquals(snapshot.identities(), whitelist.lastIdentities);
                    if (storage.linkage != null) assertTrue(storage.disabled);
                    publications++;
                    registry.publish(snapshot.identities());
                }, new FastLoginProjection(storage, () -> Thread.currentThread() == storage.main,
                        () -> {
                            assertEquals(storage.main, Thread.currentThread());
                            assertEquals(0, publications, "invalidate before disabled publication/revocation");
                            storage.disabled = true;
                        }));
    }

    private static final class ProjectionStorage implements AuthStorage {
        private final Thread main;
        private StoredProfile persisted;
        private boolean drop;
        private LinkageError linkage;
        private int linkageCalls;
        private boolean runtime;
        private boolean disabled;

        private ProjectionStorage(Thread main) { this.main = main; }
        private void offThread() { assertNotEquals(main, Thread.currentThread()); }
        private StoredProfile copy() {
            return persisted == null ? null : new StoredProfile(persisted.getRowId(), persisted.getId(),
                    persisted.getName(), persisted.isPremium(), persisted.getFloodgate(),
                    persisted.getLastIp(), persisted.getLastLogin());
        }
        @Override public StoredProfile loadProfile(String name) {
            offThread();
            if (runtime) throw new IllegalStateException("ordinary storage failure");
            if (linkage != null) {
                linkageCalls++;
                throw linkage;
            }
            return persisted != null && persisted.getName().equals(name)
                    ? copy() : new StoredProfile(null, name, false, "");
        }
        @Override public StoredProfile loadProfile(UUID uuid) {
            offThread();
            return persisted != null && uuid.equals(persisted.getId()) ? copy() : null;
        }
        @Override public void save(StoredProfile profile) {
            offThread();
            if (drop || profile.getLastIp() == null) return;
            persisted = new StoredProfile(0, profile.getId(), profile.getName(), profile.isPremium(),
                    profile.getFloodgate(), profile.getLastIp(), profile.getLastLogin());
        }
        @Override public int deleteProfile(String name) { throw new AssertionError("no deletion"); }
        @Override public void close() { throw new AssertionError("not owned"); }
    }

    @Test void replacementCompletesBeforeMetadataPublicationAndCursorAdvance() throws Exception {
        whitelist.beforeReplacement = () -> {
            assertFalse(registry.snapshot().ready());
            assertEquals(0, publications);
        };
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
        assertFalse(registry.snapshot().ready());
        assertEquals(0, publications);
        applyNext();
        assertTrue(registry.snapshot().ready());
        assertEquals(Map.of(CRACKED, ClientType.CRACKED, LICENSED, ClientType.LICENSED),
                registry.snapshot().clientTypes());
        assertEquals(1, publications);
        sync.syncNow();
        assertEquals(OptionalLong.of(42), client.next().cursor());
    }

    @Test void failedFirstReplacementDoesNotPublishOrAdvanceAndReleasesForOneRetry() throws Exception {
        whitelist.failReplacement = true;
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
        applyNext();
        assertFalse(registry.snapshot().ready());
        assertEquals(0, publications);
        sync.syncNow();
        Request retry = client.next();
        assertEquals(OptionalLong.empty(), retry.cursor());
        sync.syncNow();
        assertTrue(client.requests.isEmpty());
        whitelist.failReplacement = false;
        retry.result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
        applyNext();
        assertTrue(registry.snapshot().ready());
        assertEquals(1, publications);
    }

    @Test void publicationFailurePreservesPreviousRegistryAndCursorAndAllowsRetry() throws Exception {
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(12, IDENTITIES));
        applyNext();
        var previous = registry.snapshot();
        failPublication = true;
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(99, List.of()));
        applyNext();
        assertSame(previous, registry.snapshot());
        sync.syncNow();
        Request retry = client.next();
        assertEquals(OptionalLong.of(12), retry.cursor());
        sync.syncNow();
        assertTrue(client.requests.isEmpty());
        failPublication = false;
        retry.result().complete(new WhitelistResult.Snapshot(99, List.of()));
        applyNext();
        assertTrue(registry.snapshot().ready());
        assertTrue(registry.snapshot().clientTypes().isEmpty());
        sync.syncNow();
        assertEquals(OptionalLong.of(99), client.next().cursor());
    }

    @Test void conflictingMetadataCannotPublishOrAdvanceCursor() throws Exception {
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(12, IDENTITIES));
        applyNext();
        var previous = registry.snapshot();
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(99, List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, CRACKED),
                new WhitelistIdentity("Player", ClientType.LICENSED, CRACKED))));
        applyNext();
        assertSame(previous, registry.snapshot());
        sync.syncNow();
        assertEquals(OptionalLong.of(12), client.next().cursor());
    }

    @Test void failedLaterReplacementPreservesPublishedSnapshot() throws Exception {
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(12, IDENTITIES));
        applyNext();
        var previous = registry.snapshot();
        whitelist.failReplacement = true;
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(99, List.of()));
        applyNext();
        assertSame(previous, registry.snapshot());
        assertEquals(1, publications);
        sync.syncNow();
        assertEquals(OptionalLong.of(12), client.next().cursor());
    }

    @Test void snapshotReplacesManualUuidsUsingBothApiIdentityTypes() throws Exception {
        whitelist.uuids.add(STALE);
        whitelist.uuids.add(LICENSED);
        sync.syncNow();
        Request request = client.next();
        assertEquals(OptionalLong.empty(), request.cursor());
        request.result().complete(new WhitelistResult.Snapshot(42, IDENTITIES));
        applyNext();
        assertEquals(Set.of(CRACKED, LICENSED), whitelist.uuids);
        sync.syncNow();
        assertEquals(OptionalLong.of(42), client.next().cursor());
    }

    @Test void emptySnapshotClearsEveryUuid() throws Exception {
        whitelist.uuids.addAll(Set.of(STALE, CRACKED));
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(7, List.of()));
        applyNext();
        assertEquals(Set.of(), whitelist.uuids);
        sync.syncNow();
        assertEquals(OptionalLong.of(7), client.next().cursor());
    }

    @Test void unchangedDoesNotQueuePaperWorkButAdvancesCursor() throws Exception {
        whitelist.uuids.add(STALE);
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Unchanged(43));
        assertTrue(mainThread.isEmpty());
        assertFalse(registry.snapshot().ready());
        assertEquals(0, publications);
        assertEquals(Set.of(STALE), whitelist.uuids);
        sync.syncNow();
        assertEquals(OptionalLong.of(43), client.next().cursor());
    }

    @Test void unavailablePreservesWhitelistAndLastSuccessfulCursor() throws Exception {
        rememberCursor();
        whitelist.uuids.add(STALE);
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Unavailable());
        assertTrue(mainThread.isEmpty());
        assertEquals(Set.of(STALE), whitelist.uuids);
        sync.syncNow();
        assertEquals(OptionalLong.of(12), client.next().cursor());
    }

    @Test void unavailableBeforeFirstSuccessKeepsCursorAbsent() throws Exception {
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Unavailable());
        sync.syncNow();
        assertEquals(OptionalLong.empty(), client.next().cursor());
        assertTrue(mainThread.isEmpty());
    }

    @Test void unresolvedFetchPreventsOverlappingRequests() throws Exception {
        sync.syncNow();
        Request first = client.next();
        sync.syncNow();
        sync.syncNow();
        assertTrue(client.requests.isEmpty(), "in-flight fetch must remain the only request");
        first.result().complete(new WhitelistResult.Unchanged(2));
        sync.syncNow();
        assertEquals(OptionalLong.of(2), client.next().cursor());
    }

    @Test void queuedReplacementRemainsInFlightUntilMainThreadAppliesIt() throws Exception {
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(4, IDENTITIES));
        sync.syncNow();
        assertTrue(client.requests.isEmpty(), "queued application is still in flight");
        applyNext();
        sync.syncNow();
        assertEquals(OptionalLong.of(4), client.next().cursor());
    }

    @Test void httpCompletionCannotReadOrMutatePaperState() throws Exception {
        whitelist.uuids.add(STALE);
        sync.syncNow();
        Request request = client.next();
        Thread http = new Thread(() -> request.result().complete(
                new WhitelistResult.Snapshot(5, IDENTITIES)), "fake-http-completion");
        http.start();
        http.join(2000);
        assertFalse(http.isAlive());
        assertEquals(Set.of(STALE), whitelist.uuids);
        assertNull(whitelist.mutationThread);
        applyNext();
        assertEquals(Thread.currentThread(), whitelist.mutationThread);
        assertEquals(Set.of(CRACKED, LICENSED), whitelist.uuids);
    }

    @Test void exceptionalFetchPreservesStateAndAllowsRetry() throws Exception {
        rememberCursor();
        whitelist.uuids.add(STALE);
        sync.syncNow();
        client.next().result().completeExceptionally(new IllegalStateException("synthetic failure"));
        assertTrue(mainThread.isEmpty());
        assertEquals(Set.of(STALE), whitelist.uuids);
        sync.syncNow();
        assertEquals(OptionalLong.of(12), client.next().cursor());
    }

    @Test void cancelledFetchPreservesCursorAndAllowsRetry() throws Exception {
        rememberCursor();
        sync.syncNow();
        client.next().result().cancel(false);
        assertTrue(mainThread.isEmpty());
        sync.syncNow();
        assertEquals(OptionalLong.of(12), client.next().cursor());
    }

    @Test void synchronousClientFailureReleasesInFlightWithoutAdvancingCursor() throws Exception {
        rememberCursor();
        client.throwOnFetch = true;
        assertDoesNotThrow(sync::syncNow);
        client.throwOnFetch = false;
        sync.syncNow();
        assertEquals(OptionalLong.of(12), client.next().cursor());
    }

    @Test void failedMainThreadDispatchPreservesStateAndAllowsRetry() throws Exception {
        try (WhitelistSynchronizer rejected = new WhitelistSynchronizer(client, whitelist,
                Duration.ofHours(1), task -> { throw new IllegalStateException("scheduler stopped"); })) {
            whitelist.uuids.add(STALE);
            rejected.syncNow();
            client.next().result().complete(new WhitelistResult.Snapshot(99, IDENTITIES));
            rejected.syncNow();
            assertEquals(OptionalLong.empty(), client.next().cursor());
            assertEquals(Set.of(STALE), whitelist.uuids);
        }
    }

    @Test void failedReplacementDoesNotAdvanceCursorAndAllowsRetry() throws Exception {
        rememberCursor();
        whitelist.uuids.add(STALE);
        whitelist.failReplacement = true;
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(99, IDENTITIES));
        applyNext();
        assertEquals(Set.of(STALE), whitelist.uuids);
        sync.syncNow();
        Request retry = client.next();
        assertEquals(OptionalLong.of(12), retry.cursor());
        whitelist.failReplacement = false;
        retry.result().complete(new WhitelistResult.Snapshot(99, IDENTITIES));
        applyNext();
        assertEquals(Set.of(CRACKED, LICENSED), whitelist.uuids);
    }

    @Test void closingSuppressesAlreadyQueuedMutation() throws Exception {
        whitelist.uuids.add(STALE);
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Snapshot(6, IDENTITIES));
        sync.close();
        applyNext();
        assertEquals(Set.of(STALE), whitelist.uuids);
        assertNull(whitelist.mutationThread);
        assertFalse(registry.snapshot().ready());
        assertEquals(0, publications);
    }

    @Test void closingCancelsPendingFetchAndPreventsFurtherRequests() throws Exception {
        sync.syncNow();
        Request pending = client.next();
        sync.close();
        assertTrue(pending.result().isCancelled());
        sync.syncNow();
        sync.start();
        assertTrue(client.requests.isEmpty());
        assertTrue(mainThread.isEmpty());
        assertFalse(client.closed, "the lifecycle, not the synchronizer, owns the HTTP client");
    }

    @Test void startImmediatelySchedulesFetchWithoutWaitingForAnInterval() throws Exception {
        sync.start();
        assertEquals(OptionalLong.empty(), client.next().cursor());
    }

    @Test void startSchedulesRepeatedSerialFetchesAndCloseStopsTimer() throws Exception {
        try (WhitelistSynchronizer repeating = new WhitelistSynchronizer(
                client, whitelist, Duration.ofMillis(10), mainThread::add)) {
            repeating.start();
            client.next().result().complete(new WhitelistResult.Unchanged(8));
            Request second = client.next();
            assertEquals(OptionalLong.of(8), second.cursor());
            repeating.close();
            assertTrue(second.result().isCancelled());
            assertNull(client.requests.poll(80, TimeUnit.MILLISECONDS));
        }
    }

    @Test void nonPositiveIntervalIsRejectedBeforeScheduling() {
        assertThrows(IllegalArgumentException.class, () -> new WhitelistSynchronizer(
                client, whitelist, Duration.ZERO, mainThread::add));
        assertThrows(IllegalArgumentException.class, () -> new WhitelistSynchronizer(
                client, whitelist, Duration.ofSeconds(-1), mainThread::add));
    }

    private void rememberCursor() throws Exception {
        sync.syncNow();
        client.next().result().complete(new WhitelistResult.Unchanged(12));
    }

    private void applyNext() throws Exception {
        Runnable task = mainThread.poll(1, TimeUnit.SECONDS);
        assertNotNull(task, "snapshot must be dispatched to the main-thread executor");
        runMain(task);
    }

    private void runMain(Runnable task) {
        whitelist.onMainThread = true;
        try { task.run(); } finally { whitelist.onMainThread = false; }
    }

    private record Request(OptionalLong cursor, CompletableFuture<WhitelistResult> result) {}

    private static final class FakeClient implements GameAuthClient {
        @Override public CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] password) {
            if (password != null) java.util.Arrays.fill(password, '\0');
            return CompletableFuture.completedFuture(new CrackedLoginResult.Unavailable());
        }
        @Override public CompletionStage<SessionCheckResult> checkSession(String lease) {
            return CompletableFuture.completedFuture(new SessionCheckResult.Unavailable());
        }
        private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        private boolean throwOnFetch;
        private boolean closed;

        @Override public CompletionStage<PolicyResult> fetchPolicy(String nickname) {
            throw new AssertionError("whitelist synchronization must not fetch policy");
        }

        @Override public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) {
            if (throwOnFetch) throw new IllegalStateException("synthetic client failure");
            CompletableFuture<WhitelistResult> result = new CompletableFuture<>();
            requests.add(new Request(cursor, result));
            return result;
        }

        Request next() throws Exception {
            Request request = requests.poll(1, TimeUnit.SECONDS);
            assertNotNull(request, "synchronizer must issue a whitelist request");
            return request;
        }

        @Override public void close() { closed = true; }
    }

    private static final class RecordingWhitelist implements PaperWhitelist {
        private final Set<UUID> uuids = new HashSet<>();
        private boolean onMainThread;
        private boolean failReplacement;
        private Thread mutationThread;
        private List<WhitelistIdentity> lastIdentities;
        private Runnable beforeReplacement = () -> {};

        @Override public Set<UUID> currentUuids() {
            assertTrue(onMainThread, "Paper reads must stay on the main thread too");
            return Set.copyOf(uuids);
        }

        @Override public void replaceWith(Collection<WhitelistIdentity> identities) {
            assertTrue(onMainThread, "Paper mutation outside the main-thread executor");
            beforeReplacement.run();
            if (failReplacement) throw new IllegalStateException("synthetic mutation failure");
            mutationThread = Thread.currentThread();
            uuids.clear();
            identities.forEach(identity -> uuids.add(identity.uuid()));
            lastIdentities = List.copyOf(identities);
        }
    }
}
