package dev.sccl.pierdolauth.admission;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import java.net.InetAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdmissionServiceTest {
    private static final String UNAVAILABLE = "Authentication service is unavailable. Please try again later.";
    private final UUID id = UUID.randomUUID();
    private final WhitelistRegistry registry = new WhitelistRegistry();
    private final FakeClient client = new FakeClient();
    private final FakeClock clock = new FakeClock();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final Queue<Runnable> main = new ArrayDeque<>();
    private final Actions actions = new Actions();
    private final AdmissionService service = new AdmissionService(client, registry, Duration.ofSeconds(60),
            clock, scheduler, main::add, actions, 2);

    @Test void unreadyRegistryKicksWithoutRequest() throws Exception {
        join(connection());
        assertEquals(List.of(UNAVAILABLE), actions.messages);
        assertTrue(actions.kicked.contains(id));
        assertEquals(0, client.logins.size() + client.checks.size());
    }

    @Test void absentUuidIsDenied() throws Exception {
        registry.publish(List.of());
        join(connection());
        assertTrue(actions.kicked.contains(id));
        assertFalse(service.frozen(tokens.get(id)));
    }

    @Test void licensedUuidCannotEnter() throws Exception {
        publish(ClientType.LICENSED);
        join(connection());
        assertEquals(List.of(UNAVAILABLE), actions.messages);
        assertTrue(actions.kicked.contains(id));
    }

    @Test void crackedUuidIsFrozenAndPromptedWithoutHttp() throws Exception {
        publish(ClientType.CRACKED);
        join(connection());
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(1, actions.prompts);
        assertEquals(0, client.logins.size() + client.checks.size());
    }

    private boolean ready = true;
    @Test void authenticatedRequiresCurrentReleasedTokenAndFailsClosedAfterLifecycle() throws Exception {
        assertFalse(service.authenticated(null));
        joinFrozen();
        var old = tokens.get(id);
        assertFalse(service.authenticated(old));
        service.command(old, input());
        client.logins.getLast().complete(allowed(id, 1_300));
        assertFalse(service.authenticated(old), "Queued success is not admission");
        drain();
        assertTrue(service.authenticated(old));
        service.discard(old);
        assertFalse(service.authenticated(old));
        authenticate();
        var current = tokens.get(id);
        assertFalse(service.authenticated(old));
        assertTrue(service.authenticated(current));
        service.close();
        assertFalse(service.authenticated(current));
    }

    private AdmissionService licensedService() {
        return new AdmissionService(client, registry, Duration.ofSeconds(60), clock, scheduler,
                main::add, actions, 2, () -> ready);
    }

    @Test void premiumProofReleasesOnceCancelsQueuedExpiryAndNeverCreatesCrackedResume() throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            scheduler.fire(Duration.ofSeconds(10));
            assertFalse(licensed.authenticated(token));
            assertTrue(licensed.premiumVerified(token, id, "pLaYeR"));
            assertTrue(licensed.authenticated(token));
            assertFalse(licensed.frozen(token));
            assertFalse(licensed.premiumVerified(token, id, "Player"));
            drain();
            assertEquals(1, actions.releases);
            assertTrue(actions.kicked.isEmpty());
            assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
            licensed.quit(token);
            assertFalse(licensed.authenticated(token));
            publish(ClientType.CRACKED);
            var cracked = licensed.join(connection());
            assertTrue(licensed.frozen(cracked));
            assertEquals(1, actions.prompts);
            assertTrue(client.checks.isEmpty());
            assertTrue(client.logins.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void invalidPremiumProofNeverGrantsOrCancelsDeadline(int mismatch) throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var connection = mismatch == 8
                    ? new ConnectionIdentity(id, "Other", InetAddress.getLoopbackAddress()) : connection();
            var token = licensed.join(connection);
            UUID proofId = id;
            String name = "Player";
            switch (mismatch) {
                case 0 -> proofId = UUID.randomUUID();
                case 1 -> name = "Other";
                case 2 -> publish(ClientType.CRACKED);
                case 3 -> registry.publish(List.of());
                case 4 -> ready = false;
                case 5 -> licensed.join(connection());
                case 6 -> licensed.quit(token);
                case 7 -> licensed.close();
            }
            assertFalse(licensed.premiumVerified(token, proofId, name));
            assertEquals(0, actions.releases);
            assertTrue(client.logins.isEmpty());
            assertTrue(client.checks.isEmpty());
            if (mismatch < 5 || mismatch == 8) {
                assertTrue(licensed.frozen(token));
                assertEquals(1, scheduler.live(Duration.ofSeconds(10)));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void publishedRevocationRemovesOriginalLicensedPendingOrAuthenticatedButNotCracked(boolean verified) throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            if (verified) assertTrue(licensed.premiumVerified(token, id, "Player"));
            licensed.revokeLicensed(registry.snapshot());
            assertTrue(actions.kicked.isEmpty());
            publish(ClientType.CRACKED);
            licensed.revokeLicensed(registry.snapshot());
            assertEquals(List.of("Your authentication session is no longer valid. Please reconnect."), actions.messages);
            assertFalse(licensed.frozen(token));
            assertEquals(0, scheduler.live(Duration.ofSeconds(10)));
            licensed.quit(token);
            assertTrue(client.checks.isEmpty());
            actions.messages.clear(); actions.kicked.clear();
            var cracked = licensed.join(connection());
            registry.publish(List.of());
            licensed.revokeLicensed(registry.snapshot());
            assertTrue(licensed.frozen(cracked));
            assertTrue(actions.kicked.isEmpty());
        }
    }

    @Test void unreadyOrUnpublishedRevocationCannotChangeCurrentLicensedEntry() throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            licensed.revokeLicensed(new WhitelistRegistry().snapshot());
            WhitelistRegistry other = new WhitelistRegistry(); other.publish(List.of());
            licensed.revokeLicensed(other.snapshot());
            assertTrue(licensed.frozen(token));
            assertTrue(actions.kicked.isEmpty());
        }
    }

    @Test void licensedRevocationToleratesSynchronousQuitAndQueuedTimeoutReentry() throws Exception {
        UUID second = UUID.randomUUID();
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id),
                new WhitelistIdentity("Second", ClientType.LICENSED, second)));
        try (var licensed = licensedService()) {
            var first = licensed.join(connection());
            var other = licensed.join(new ConnectionIdentity(second, "Second", InetAddress.getLoopbackAddress()));
            scheduler.fire(Duration.ofSeconds(10));
            actions.onKick = uuid -> { licensed.quit(first); licensed.quit(other); };
            registry.publish(List.of());
            assertDoesNotThrow(() -> licensed.revokeLicensed(registry.snapshot()));
            drain();
            // Synchronous quit may remove the other entry before iteration reaches it.
            assertFalse(licensed.frozen(first));
            assertFalse(licensed.frozen(other));
            assertFalse(actions.kicked.isEmpty());
            assertTrue(actions.messages.stream().allMatch(message -> message.equals(
                    "Your authentication session is no longer valid. Please reconnect.")));
            assertTrue(client.checks.isEmpty());
        }
    }

    @Test void currentUnreadySnapshotAndOriginalCrackedEntryCannotAcceptPremiumProof() throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            registry.publish(new WhitelistRegistry().snapshot());
            assertFalse(licensed.premiumVerified(token, id, "Player"));
            assertTrue(licensed.frozen(token));
            publish(ClientType.CRACKED);
            var cracked = licensed.join(connection());
            publish(ClientType.LICENSED);
            assertFalse(licensed.premiumVerified(cracked, id, "Player"));
            registry.publish(List.of());
            licensed.revokeLicensed(registry.snapshot());
            assertTrue(licensed.frozen(cracked));
            assertTrue(actions.kicked.isEmpty());
        }
    }

    @Test void pluginPublicationPublishesBeforeRevokingAndToleratesAdmissionNotConstructedYet() throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            var publisher = dev.sccl.pierdolauth.PierdolAuthPlugin.publication(registry, () -> licensed);
            publisher.accept(new WhitelistResult.Snapshot(2, List.of()));
            assertTrue(registry.snapshot().ready());
            assertTrue(registry.snapshot().identities().isEmpty());
            assertFalse(licensed.frozen(token), "Published revocation must invalidate licensed connection");
            assertTrue(actions.kicked.contains(id));
            dev.sccl.pierdolauth.PierdolAuthPlugin.publication(registry, () -> null)
                    .accept(new WhitelistResult.Snapshot(3, List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id))));
            assertEquals(ClientType.CRACKED, registry.snapshot().clientTypes().get(id));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void synchronizerRevokesOnlyAfterSuccessfulOpenPublication(int outcome) throws Exception {
        publish(ClientType.LICENSED);
        BlockingQueue<Runnable> publications = new LinkedBlockingQueue<>();
        client.whitelist = CompletableFuture.completedFuture(new WhitelistResult.Snapshot(2, List.of()));
        try (var licensed = licensedService();
                var synchronizer = new dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer(client,
                        new dev.sccl.pierdolauth.whitelist.PaperWhitelist() {
                            public Set<UUID> currentUuids() { return Set.of(); }
                            public void replaceWith(Collection<WhitelistIdentity> identities) {}
                        }, Duration.ofHours(1), publications::add,
                        dev.sccl.pierdolauth.PierdolAuthPlugin.publication(registry, () -> licensed),
                        snapshot -> outcome != 1)) {
            var token = licensed.join(connection());
            synchronizer.syncNow();
            Runnable apply = publications.poll(2, TimeUnit.SECONDS); assertNotNull(apply); apply.run();
            Runnable complete = publications.poll(2, TimeUnit.SECONDS); assertNotNull(complete);
            assertTrue(licensed.frozen(token));
            assertEquals(ClientType.LICENSED, registry.snapshot().clientTypes().get(id));
            if (outcome == 2) synchronizer.close();
            complete.run();
            assertEquals(outcome == 0, actions.kicked.contains(id));
            assertEquals(outcome != 0, licensed.frozen(token));
            assertEquals(outcome == 0, registry.snapshot().identities().isEmpty());
        }
    }

    @Test void readyLicensedJoinsFrozenWithoutPasswordOrSessionAndExpiresAtTenSeconds() throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            assertTrue(licensed.frozen(token), "Ready licensed connection must remain frozen pending proof");
            assertTrue(actions.kicked.isEmpty());
            assertEquals(0, actions.prompts);
            char[] password = input();
            licensed.command(token, password);
            assertCleared(password);
            licensed.command(token, new char[0]);
            assertEquals(0, actions.prompts);
            assertTrue(client.logins.isEmpty());
            assertTrue(client.checks.isEmpty());
            assertEquals(1, scheduler.live(Duration.ofSeconds(10)));
            assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
            scheduler.fire(Duration.ofSeconds(10));
            assertTrue(actions.kicked.isEmpty(), "Timer must dispatch to main thread");
            drain();
            assertEquals(List.of(UNAVAILABLE), actions.messages);
            assertFalse(licensed.frozen(token));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void queuedLicensedExpiryCannotAffectReplacementQuitOrClose(int lifecycle) throws Exception {
        publish(ClientType.LICENSED);
        try (var licensed = licensedService()) {
            var token = licensed.join(connection());
            assertTrue(licensed.frozen(token));
            scheduler.fire(Duration.ofSeconds(10));
            switch (lifecycle) {
                case 0 -> licensed.join(connection());
                case 1 -> licensed.quit(token);
                case 2 -> licensed.close();
            }
            drain();
            assertTrue(actions.kicked.isEmpty());
            assertTrue(client.checks.isEmpty());
        }
    }

    @Test void readinessIsCheckedAgainAtLicensedJoin() throws Exception {
        publish(ClientType.LICENSED);
        ready = false;
        try (var licensed = licensedService()) {
            assertFalse(licensed.frozen(licensed.join(connection())));
            assertEquals(List.of(UNAVAILABLE), actions.messages);
            assertEquals(0, scheduler.live(Duration.ofSeconds(10)));
        }
    }

    @Test void addressOwnershipAndComparisonAreDefensive() throws Exception {
        byte[] source = {127, 0, 0, 1};
        ConnectionIdentity identity = new ConnectionIdentity(id, "Player", InetAddress.getByAddress(source));
        source[3] = 2;
        byte[] returned = identity.address();
        returned[3] = 3;
        assertTrue(identity.sameAddress(connection()));
        assertEquals(id, identity.uuid());
        assertEquals("Player", identity.nickname());
    }

    private final Map<UUID, AdmissionService.ConnectionToken> tokens = new HashMap<>();
    private AdmissionService.ConnectionToken join(ConnectionIdentity identity) {
        var token = service.join(identity);
        tokens.put(identity.uuid(), token);
        return token;
    }

    private void publish(ClientType type) {
        registry.publish(List.of(new WhitelistIdentity("Player", type, id)));
    }

    private ConnectionIdentity connection() throws Exception {
        return new ConnectionIdentity(id, "Player", InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    }

    private void drain() { while (!main.isEmpty()) main.remove().run(); }

    @Test void commandOutsideFrozenStateDoesNotCallApiAndClearsInput() {
        char[] input = input();
        service.command(tokens.get(id), input);
        assertEquals(0, client.logins.size());
        assertCleared(input);
    }

    @Test void duplicateCommandsCannotOverlapAndCopiesHaveTerminalCleanup() throws Exception {
        joinFrozen();
        char[] input = input();
        service.command(tokens.get(id), input);
        assertCleared(input);
        assertNotSame(input, client.received);
        assertTrue(client.received.length > 0);
        char[] duplicate = input();
        service.command(tokens.get(id), duplicate);
        assertCleared(duplicate);
        assertEquals(1, client.logins.size());
        client.logins.getFirst().complete(new CrackedLoginResult.Denied());
        assertTrue(actions.messages.isEmpty());
        assertCleared(client.received);
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(List.of("Invalid password."), actions.messages);
        service.command(tokens.get(id), input());
        assertEquals(2, client.logins.size());
    }

    @Test void unavailableLoginIsFrozenWithExactMessage() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getFirst().complete(new CrackedLoginResult.Unavailable());
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(List.of(UNAVAILABLE), actions.messages);
    }

    @Test void exceptionalLoginIsUnavailableAndClearsCopy() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getFirst().completeExceptionally(new IllegalStateException());
        assertCleared(client.received);
        drain();
        assertEquals(List.of(UNAVAILABLE), actions.messages);
        assertTrue(service.frozen(tokens.get(id)));
    }

    @Test void mismatchedLoginCannotRetainAuthorization() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getFirst().complete(allowed(UUID.randomUUID(), 1_300));
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        assertEquals(List.of(UNAVAILABLE), actions.messages);
        assertEquals(0, client.checks.size());
    }

    @Test void matchingLoginReleasesOnlyOnMainDispatcherAndSchedulesConfiguredHeartbeat() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getFirst().complete(allowed(id, 1_300));
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        drain();
        assertFalse(service.frozen(tokens.get(id)));
        assertEquals(1, actions.releases);
        assertTrue(scheduler.jobs.stream().anyMatch(job -> !job.cancelled && job.delay.equals(Duration.ofSeconds(60))));
        service.command(tokens.get(id), input());
        assertEquals(1, client.logins.size());
    }

    @Test void incompleteCommandPromptsWithoutRequest() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), new char[0]);
        assertEquals(2, actions.prompts);
        assertEquals(0, client.logins.size());
    }

    @Test void synchronousClientFailureIsUnavailableWithoutClearingClientOwnedCopy() throws Exception {
        joinFrozen();
        client.throwLogin = true;
        char[] input = input();
        char[] expected = input.clone();
        client.original = input;
        service.command(tokens.get(id), input);
        assertCleared(input);
        assertTrue(client.originalClearedAtStart);
        assertNotSame(input, client.received);
        assertTrue(Arrays.equals(expected, client.received), "Throwing invocation retains client ownership");
        assertTrue(actions.messages.isEmpty());
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(List.of(UNAVAILABLE), actions.messages);
        assertTrue(Arrays.equals(expected, client.received), "Unavailable handling must not clear client-owned input");
        client.clearOwnedInput();
        assertCleared(client.received);
        Arrays.fill(expected, '\0');
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void throwingAfterRetainingInputPreservesClientOwnershipAcrossLifecycleAndDispatch(int scenario) throws Exception {
        joinFrozen();
        client.throwLogin = true;
        char[] original = input();
        char[] expected = original.clone();
        service.command(tokens.get(id), original);
        assertCleared(original);
        assertTrue(Arrays.equals(expected, client.received), "Admission must not clear input after invocation throws");
        if (scenario >= 3) drain();
        switch (scenario % 3) {
            case 0 -> service.quit(tokens.get(id));
            case 1 -> join(connection());
            case 2 -> service.close();
        }
        assertTrue(CompletableFuture.supplyAsync(() -> client.consumeMatches(expected)).join(),
                "Lifecycle must preserve input retained by the throwing client");
        drain();
        assertTrue(Arrays.equals(expected, client.received), "Dispatch must preserve client-owned input");
        assertEquals(0, actions.releases);
        client.clearOwnedInput();
        assertCleared(client.received);
        Arrays.fill(expected, '\0');
    }

    private void joinFrozen() throws Exception { publish(ClientType.CRACKED); join(connection()); }
    private static char[] input() { return UUID.randomUUID().toString().toCharArray(); }
    private static String opaque() { return UUID.randomUUID().toString(); }
    private static void assertCleared(char[] input) {
        boolean cleared = true;
        for (char value : input) cleared &= value == 0;
        assertTrue(cleared, "Mutable input must be cleared");
    }
    private static CrackedLoginResult.Allowed allowed(UUID uuid, long expiry) {
        return new CrackedLoginResult.Allowed(uuid, opaque(), expiry);
    }

    @Test void heartbeatContinuationIsSilentAndSerialized() throws Exception {
        authenticate();
        fireHeartbeat();
        assertEquals(1, client.checks.size());
        assertTrue(actions.messages.isEmpty());
        assertFalse(service.frozen(tokens.get(id)));
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
        client.checks.getFirst().complete(continued(id, 1_400));
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
        drain();
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
        assertEquals(1, actions.releases);
        assertTrue(actions.messages.isEmpty());
    }

    @Test void unavailableHeartbeatNeverKicksEvenPastLocalExpiry() throws Exception {
        authenticate();
        clock.now = Instant.ofEpochSecond(1_500);
        fireHeartbeat();
        client.checks.getFirst().complete(new SessionCheckResult.Unavailable());
        drain();
        assertFalse(service.frozen(tokens.get(id)));
        assertTrue(actions.kicked.isEmpty());
        assertTrue(actions.messages.isEmpty());
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
        fireHeartbeat();
        client.checks.getLast().completeExceptionally(new IllegalStateException());
        drain();
        assertTrue(actions.kicked.isEmpty());
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
    }

    @Test void explicitRevocationImmediatelyKicksAndRemovesActiveState() throws Exception {
        authenticate();
        fireHeartbeat();
        client.checks.getFirst().complete(new SessionCheckResult.Disconnected());
        assertTrue(actions.kicked.isEmpty());
        drain();
        assertTrue(actions.kicked.contains(id));
        assertEquals(List.of("Your authentication session is no longer valid. Please reconnect."), actions.messages);
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
        service.command(tokens.get(id), input());
        assertEquals(1, client.logins.size());
    }

    @Test void mismatchedHeartbeatKicks() throws Exception {
        authenticate();
        fireHeartbeat();
        client.checks.getFirst().complete(continued(UUID.randomUUID(), 1_400));
        drain();
        assertTrue(actions.kicked.contains(id));
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
    }

    @Test void oldHeartbeatCannotKickNewGenerationOrOverlapItsLogin() throws Exception {
        authenticate();
        fireHeartbeat();
        join(connection());
        service.command(tokens.get(id), input());
        assertEquals(1, client.logins.size());
        client.checks.getFirst().complete(new SessionCheckResult.Disconnected());
        drain();
        assertTrue(actions.kicked.isEmpty());
        assertTrue(service.frozen(tokens.get(id)));
        service.command(tokens.get(id), input());
        assertEquals(2, client.logins.size());
    }

    @Test void staleLoginCannotReleaseNewConnection() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        join(connection());
        client.logins.getFirst().complete(allowed(id, 1_300));
        drain();
        assertEquals(0, actions.releases);
        assertTrue(service.frozen(tokens.get(id)));
    }

    private static SessionCheckResult.Continued continued(UUID uuid, long expiry) {
        return new SessionCheckResult.Continued(uuid, opaque(), expiry);
    }
    private void authenticate() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getLast().complete(allowed(id, 1_300));
        drain();
    }
    private void fireHeartbeat() { scheduler.fire(Duration.ofSeconds(60)); drain(); }

    @Test void quitStopsHeartbeatAndFinalRenewalEnablesBackendControlledResume() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
        assertEquals(1, client.checks.size());
        client.checks.getFirst().complete(continued(id, 1_450));
        drain();
        clock.now = Instant.ofEpochSecond(1_350);
        join(connection());
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(1, actions.prompts);
        assertEquals(2, client.checks.size());
        client.checks.getLast().complete(continued(id, 1_650));
        assertEquals(1, actions.releases);
        drain();
        assertFalse(service.frozen(tokens.get(id)));
        assertEquals(2, actions.releases);
        assertEquals(1, actions.prompts);
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
    }

    @Test void differentAddressFallsBackToPrompt() throws Exception {
        quitRenewed();
        join(new ConnectionIdentity(id, "Player", InetAddress.getByAddress(new byte[] {127, 0, 0, 2})));
        assertEquals(2, actions.prompts);
        assertEquals(1, client.checks.size());
        assertTrue(service.frozen(tokens.get(id)));
    }

    @Test void expiryBoundaryFallsBackWithoutBackendCheck() throws Exception {
        quitRenewed();
        clock.now = Instant.ofEpochSecond(1_400);
        join(connection());
        assertEquals(2, actions.prompts);
        assertEquals(1, client.checks.size());
    }

    @Test void failedFinalRenewalDoesNotExtendLastKnownWindow() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        client.checks.getFirst().complete(new SessionCheckResult.Unavailable());
        drain();
        clock.now = Instant.ofEpochSecond(1_300);
        join(connection());
        assertEquals(2, actions.prompts);
        assertEquals(1, client.checks.size());
        assertTrue(actions.kicked.isEmpty());
    }

    @Test void finalRevocationDropsResumeRecordWithoutKickingOfflinePlayer() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        client.checks.getFirst().complete(new SessionCheckResult.Disconnected());
        drain();
        join(connection());
        assertEquals(2, actions.prompts);
        assertEquals(1, client.checks.size());
        assertTrue(actions.kicked.isEmpty());
    }

    @Test void resumeUnavailableFallsBackToPromptWithoutKick() throws Exception {
        resumeFails(new SessionCheckResult.Unavailable());
    }

    @Test void resumeDisconnectedFallsBackToPromptWithoutKick() throws Exception {
        resumeFails(new SessionCheckResult.Disconnected());
    }

    @Test void resumeUuidMismatchCannotRelease() throws Exception {
        resumeFails(continued(UUID.randomUUID(), 1_400));
    }

    private void resumeFails(SessionCheckResult result) throws Exception {
        quitRenewed();
        join(connection());
        assertEquals(1, actions.prompts);
        service.command(tokens.get(id), input());
        assertEquals(1, client.logins.size());
        client.checks.getLast().complete(result);
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(2, actions.prompts);
        assertEquals(1, actions.releases);
        assertTrue(actions.kicked.isEmpty());
        service.command(tokens.get(id), input());
        assertEquals(2, client.logins.size());
    }

    @Test void immediateReconnectWaitsForFinalRenewalButNeverUsesItsGrant() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        join(connection());
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(1, actions.prompts);
        assertEquals(1, client.checks.size());
        client.checks.getFirst().complete(continued(id, 1_400));
        drain();
        assertEquals(1, actions.releases);
        assertEquals(2, client.checks.size());
        client.checks.getLast().complete(continued(id, 1_400));
        drain();
        assertEquals(2, actions.releases);
        assertEquals(1, actions.prompts);
    }

    @Test void quitDuringHeartbeatDefersFinalRenewalWithoutOverlap() throws Exception {
        authenticate();
        fireHeartbeat();
        service.quit(tokens.get(id));
        assertEquals(1, client.checks.size());
        client.checks.getFirst().complete(new SessionCheckResult.Disconnected());
        drain();
        assertTrue(actions.kicked.isEmpty());
        assertEquals(2, client.checks.size());
        client.checks.getLast().complete(continued(id, 1_400));
        drain();
        join(connection());
        assertEquals(3, client.checks.size());
        assertEquals(1, actions.prompts);
    }

    @Test void quitDuringLoginCannotCreateResumeOrAuthorizeReconnect() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        service.quit(tokens.get(id));
        join(connection());
        client.logins.getFirst().complete(allowed(id, 1_300));
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        assertEquals(0, client.checks.size());
        assertEquals(2, actions.prompts);
    }

    @Test void heartbeatRefreshIsUsedAsLastKnownQuitWindow() throws Exception {
        authenticate();
        fireHeartbeat();
        client.checks.getFirst().complete(continued(id, 1_500));
        drain();
        service.quit(tokens.get(id));
        client.checks.getLast().complete(new SessionCheckResult.Unavailable());
        drain();
        clock.now = Instant.ofEpochSecond(1_400);
        join(connection());
        assertEquals(1, actions.prompts);
        assertEquals(3, client.checks.size());
    }

    @Test void closeCancelsWorkAndSuppressesQueuedAndLateCompletions() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        service.close();
        assertTrue(client.received[0] != 0);
        client.logins.getFirst().complete(allowed(id, 1_300));
        drain();
        assertEquals(0, actions.releases);
        assertFalse(service.frozen(tokens.get(id)));
        assertTrue(scheduler.jobs.stream().allMatch(job -> job.cancelled));
        join(connection());
        service.command(tokens.get(id), input());
        assertEquals(1, actions.prompts);
        assertEquals(1, client.logins.size());
        service.close();
    }

    @Test void closeDiscardsResumeAndNewServiceRequiresPassword() throws Exception {
        quitRenewed();
        service.close();
        AdmissionService restarted = new AdmissionService(client, registry, Duration.ofSeconds(60),
                clock, scheduler, main::add, actions, 2);
        var restartedToken = restarted.join(connection());
        assertTrue(restarted.frozen(restartedToken));
        assertEquals(2, actions.prompts);
        assertEquals(1, client.checks.size());
        restarted.close();
    }

    @Test void closedServiceSuppressesQueuedRevocationAndTimerCallbacks() throws Exception {
        authenticate();
        fireHeartbeat();
        client.checks.getFirst().complete(new SessionCheckResult.Disconnected());
        service.close();
        drain();
        assertTrue(actions.kicked.isEmpty());
        assertTrue(scheduler.jobs.stream().allMatch(job -> job.cancelled));
    }

    @Test void resumeCacheEvictsOldestAtCapacity() throws Exception {
        quitRenewed();
        for (int index = 0; index < 2; index++) {
            UUID other = UUID.randomUUID();
            String otherNickname = "Player" + index;
            registry.publish(List.of(new WhitelistIdentity(otherNickname, ClientType.CRACKED, other),
                    new WhitelistIdentity("Player", ClientType.CRACKED, id)));
            join(new ConnectionIdentity(other, otherNickname, InetAddress.getByAddress(new byte[] {127, 0, 0, 1})));
            service.command(tokens.get(other), input());
            client.logins.getLast().complete(allowed(other, 1_300));
            drain();
            service.quit(tokens.get(other));
            client.checks.getLast().complete(continued(other, 1_400));
            drain();
        }
        join(connection());
        assertEquals(4, actions.prompts);
        assertEquals(3, client.checks.size());
    }

    @Test void scheduledCleanupDoesNotExtendExpiredResume() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        clock.now = Instant.ofEpochSecond(1_500);
        scheduler.fire(Duration.ofMinutes(5));
        drain();
        client.checks.getFirst().complete(continued(id, 1_800));
        drain();
        join(connection());
        assertEquals(2, actions.prompts);
        assertEquals(1, client.checks.size());
        service.close();
        assertTrue(scheduler.jobs.stream().allMatch(job -> job.cancelled));
    }

    private void quitRenewed() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        client.checks.getLast().complete(continued(id, 1_400));
        drain();
    }

    @Test void callbacksArrivingAfterCloseAreNotQueued() throws Exception {
        authenticate();
        fireHeartbeat();
        service.close();
        client.checks.getFirst().complete(continued(id, 1_400));
        assertTrue(main.isEmpty());
        assertEquals(1, actions.releases);
    }

    @Test void queuedLoginGrantCannotSurviveCloseAndRestart() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getFirst().complete(allowed(id, 1_300));
        service.close();
        drain();
        assertEquals(0, actions.releases);
        var restarted = new AdmissionService(client, registry, Duration.ofSeconds(60), clock,
                scheduler, main::add, actions, 2);
        var restartedToken = restarted.join(connection());
        assertTrue(restarted.frozen(restartedToken));
        assertEquals(2, actions.prompts);
        restarted.close();
    }

    @Test void expiredCandidateWaitingForOlderRequestCannotStartResume() throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        join(connection());
        clock.now = Instant.ofEpochSecond(1_300);
        client.checks.getFirst().complete(continued(id, 1_600));
        drain();
        assertEquals(1, client.checks.size());
        assertEquals(1, actions.releases);
        assertEquals(2, actions.prompts);
        assertTrue(service.frozen(tokens.get(id)));
    }

    @Test void successfulCheckRotatesOnlyTheMatchingInMemoryGrant() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        var first = allowed(id, 1_300);
        client.logins.getFirst().complete(first);
        drain();
        assertEquals("Player", client.receivedNickname);
        fireHeartbeat();
        assertTrue(first.lease().equals(client.checkInputs.getFirst()));
        var refreshed = continued(id, 1_400);
        client.checks.getFirst().complete(refreshed);
        drain();
        fireHeartbeat();
        assertTrue(refreshed.lease().equals(client.checkInputs.getLast()));
        assertTrue(actions.messages.isEmpty());
    }

    @Test void expiredActiveQuitStillAttemptsFinalRenewal() throws Exception {
        authenticate();
        clock.now = Instant.ofEpochSecond(2_000);
        service.quit(tokens.get(id));
        assertEquals(1, client.checks.size());
        client.checks.getFirst().complete(continued(id, 2_300));
        drain();
        join(connection());
        assertEquals(1, actions.prompts);
        assertEquals(2, client.checks.size());
        assertTrue(service.frozen(tokens.get(id)));
    }

    @Test void backendOutagePastLocalExpiryNeverKicksActiveConnection() throws Exception {
        authenticate();
        clock.now = Instant.ofEpochSecond(2_000);
        fireHeartbeat();
        client.checks.getFirst().complete(new SessionCheckResult.Unavailable());
        drain();
        assertTrue(actions.kicked.isEmpty());
        assertFalse(service.frozen(tokens.get(id)));
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
    }

    @Test void backgroundCompletionAndTimerMustDispatchBeforeChangingStateOrCallingApi() throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        Thread worker = new Thread(() -> client.logins.getFirst().complete(allowed(id, 1_300)));
        worker.start();
        worker.join();
        assertCleared(client.received);
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        drain();
        assertEquals(1, actions.releases);
        scheduler.fire(Duration.ofSeconds(60));
        assertEquals(0, client.checks.size());
        drain();
        assertEquals(1, client.checks.size());
    }

    @Test void resumeCapacityEvictsOldestWhileRetainingNewerUuid() throws Exception {
        quitRenewed();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id),
                new WhitelistIdentity("Second", ClientType.CRACKED, second),
                new WhitelistIdentity("Third", ClientType.CRACKED, third)));
        for (UUID other : List.of(second, third)) {
            join(new ConnectionIdentity(other, "Other", InetAddress.getByAddress(new byte[] {127, 0, 0, 1})));
            service.command(tokens.get(other), input());
            client.logins.getLast().complete(allowed(other, 1_300));
            drain();
            service.quit(tokens.get(other));
            client.checks.getLast().complete(continued(other, 1_400));
            drain();
        }
        int before = client.checks.size();
        join(connection());
        assertEquals(before, client.checks.size());
        assertEquals(4, actions.prompts);
        join(new ConnectionIdentity(second, "Second", InetAddress.getByAddress(new byte[] {127, 0, 0, 1})));
        assertEquals(before + 1, client.checks.size());
        assertEquals(4, actions.prompts);
    }

    @Test void lateOldQuitCannotRemoveFrozenReplacement() throws Exception {
        joinFrozen();
        var old = tokens.get(id);
        join(connection());
        service.quit(old); // Late quit from the first connection.
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        assertEquals(0, client.checks.size());
    }

    @Test void lateOldQuitCannotRemovePendingReplacement() throws Exception {
        joinFrozen();
        var old = tokens.get(id);
        join(connection());
        service.command(tokens.get(id), input());
        service.quit(old);
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        assertEquals(0, client.checks.size());
        client.logins.getFirst().complete(allowed(id, 1_300));
        drain();
        assertEquals(1, actions.releases);
    }

    @Test void lateOldQuitCannotCancelAuthenticatedReplacementHeartbeatOrCreateResume() throws Exception {
        joinFrozen();
        var old = tokens.get(id);
        authenticate();
        service.quit(old);
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
        assertEquals(0, client.checks.size());
        assertEquals(1, actions.releases);
        join(connection());
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(3, actions.prompts);
        assertEquals(0, client.checks.size());
    }

    @Test void originalCommandInputIsClearedBeforeClientStarts() throws Exception {
        joinFrozen();
        char[] original = input();
        client.original = original;
        service.command(tokens.get(id), original);
        assertTrue(client.originalClearedAtStart);
        assertCleared(original);
    }

    @Test void quitCannotMutateDelayedClientOwnedInput() throws Exception {
        delayedConsumptionSurvives(() -> service.quit(tokens.get(id)));
    }

    @Test void replacementCannotMutateDelayedClientOwnedInput() throws Exception {
        delayedConsumptionSurvives(() -> {
            try { join(connection()); } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    @Test void closeCannotMutateDelayedClientOwnedInput() throws Exception {
        delayedConsumptionSurvives(service::close);
    }

    private void delayedConsumptionSurvives(Runnable lifecycle) throws Exception {
        joinFrozen();
        client.delayedConsumption = true;
        char[] original = input();
        char[] expected = original.clone();
        service.command(tokens.get(id), original);
        assertCleared(original);
        assertNotSame(original, client.received);
        lifecycle.run();
        // The client reads on a worker only after the lifecycle operation has completed.
        assertTrue(CompletableFuture.supplyAsync(() -> client.consumeMatches(expected)).join(),
                "Lifecycle must not change client-owned input before consumption");
        client.logins.getFirst().complete(new CrackedLoginResult.Denied());
        drain();
        assertTrue(Arrays.equals(expected, client.received), "Admission must not perform owner cleanup");
        client.clearOwnedInput();
        assertCleared(client.received);
        Arrays.fill(expected, '\0');
    }

    @Test void staleTokenCannotSubmitCommandOrObserveReplacementFrozenState() throws Exception {
        joinFrozen();
        var old = tokens.get(id);
        join(connection());
        assertFalse(service.frozen(old));
        char[] original = input();
        service.command(old, original);
        assertCleared(original);
        assertTrue(client.logins.isEmpty());
        assertTrue(service.frozen(tokens.get(id)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void unusableLoginSuccessCannotReleaseOrCreateResume(int shape) throws Exception {
        joinFrozen();
        service.command(tokens.get(id), input());
        client.logins.getFirst().complete(new CrackedLoginResult.Allowed(id, unusableLease(shape), unusableExpiry(shape)));
        drain();
        assertTrue(service.frozen(tokens.get(id)));
        assertEquals(0, actions.releases);
        assertEquals(List.of(UNAVAILABLE), actions.messages);
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
        service.quit(tokens.get(id));
        join(connection());
        assertTrue(client.checks.isEmpty());
        assertEquals(2, actions.prompts);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void unusableResumeSuccessFallsBackWithoutRelease(int shape) throws Exception {
        resumeFails(new SessionCheckResult.Continued(id, unusableLease(shape), unusableExpiry(shape)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void unusableHeartbeatSuccessKeepsLastKnownWindowAndRetries(int shape) throws Exception {
        authenticate();
        fireHeartbeat();
        client.checks.getFirst().complete(new SessionCheckResult.Continued(id, unusableLease(shape), unusableExpiry(shape)));
        drain();
        assertTrue(actions.kicked.isEmpty());
        assertTrue(actions.messages.isEmpty());
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
        service.quit(tokens.get(id));
        client.checks.getLast().complete(new SessionCheckResult.Unavailable());
        drain();
        clock.now = Instant.ofEpochSecond(1_200);
        join(connection());
        assertEquals(3, client.checks.size());
        assertEquals(1, actions.prompts);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void unusableFinalSuccessCannotExtendResumeWindow(int shape) throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        client.checks.getFirst().complete(new SessionCheckResult.Continued(id, unusableLease(shape), unusableExpiry(shape)));
        drain();
        clock.now = Instant.ofEpochSecond(1_300);
        join(connection());
        assertEquals(1, client.checks.size());
        assertEquals(2, actions.prompts);
        assertTrue(service.frozen(tokens.get(id)));
    }

    private static String unusableLease(int shape) {
        return switch (shape) { case 0 -> null; case 1 -> ""; case 2 -> " \t"; default -> opaque(); };
    }
    private static long unusableExpiry(int shape) {
        return switch (shape) { case 3 -> 1_000; case 4 -> 999; default -> 1_900; };
    }

    @Test void staleSuccessfulResumeCannotAuthorizeReplacement() throws Exception {
        quitRenewed();
        var old = join(connection());
        var replacement = join(connection());
        client.checks.getLast().complete(continued(id, 1_500));
        drain();
        assertFalse(service.frozen(old));
        assertTrue(service.frozen(replacement));
        assertEquals(1, actions.releases);
        assertEquals(2, actions.prompts);
        assertEquals(0, scheduler.live(Duration.ofSeconds(60)));
        service.command(replacement, input());
        assertEquals(2, client.logins.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleFinalRenewalCannotChangeNewerResumeRecord(boolean revoked) throws Exception {
        authenticate();
        service.quit(tokens.get(id));
        client.checks.getFirst().complete(revoked ? new SessionCheckResult.Disconnected() : continued(id, 1_900));
        Runnable oldCompletion = main.remove();
        oldCompletion.run();
        // the per-UUID gate normally prevents overlapping final generations. replay an already
        join(new ConnectionIdentity(id, "Player", InetAddress.getByAddress(new byte[] {127, 0, 0, 2})));
        service.command(tokens.get(id), input());
        var newer = allowed(id, 1_300);
        client.logins.getLast().complete(newer);
        drain();
        service.quit(tokens.get(id));
        oldCompletion.run();
        client.checks.getLast().complete(new SessionCheckResult.Unavailable());
        drain();
        clock.now = Instant.ofEpochSecond(revoked ? 1_200 : 1_300);
        join(new ConnectionIdentity(id, "Player", InetAddress.getByAddress(new byte[] {127, 0, 0, 2})));
        assertEquals(revoked ? 2 : 3, actions.prompts);
        assertEquals(revoked ? 3 : 2, client.checks.size());
        assertTrue(service.frozen(tokens.get(id)));
        if (revoked) assertTrue(newer.lease().equals(client.checkInputs.getLast()));
    }

    @Test void synchronousHeartbeatFailureKeepsSessionAndSchedulesRetry() throws Exception {
        authenticate();
        client.throwCheck = true;
        fireHeartbeat();
        assertTrue(actions.kicked.isEmpty());
        assertTrue(actions.messages.isEmpty());
        assertFalse(service.frozen(tokens.get(id)));
        assertEquals(1, scheduler.live(Duration.ofSeconds(60)));
        client.throwCheck = false;
        fireHeartbeat();
        assertEquals(1, client.checks.size());
    }

    @Test void synchronousResumeFailureFallsBackAndReleasesRequestGate() throws Exception {
        quitRenewed();
        client.throwCheck = true;
        join(connection());
        assertEquals(1, actions.prompts);
        drain();
        assertEquals(2, actions.prompts);
        assertTrue(service.frozen(tokens.get(id)));
        assertTrue(actions.kicked.isEmpty());
        service.command(tokens.get(id), input());
        assertEquals(2, client.logins.size());
    }

    @Test void synchronousFinalFailureDoesNotExtendWindowOrBlockNewLogin() throws Exception {
        authenticate();
        client.throwCheck = true;
        service.quit(tokens.get(id));
        drain();
        clock.now = Instant.ofEpochSecond(1_300);
        join(connection());
        assertEquals(2, actions.prompts);
        assertTrue(actions.kicked.isEmpty());
        service.command(tokens.get(id), input());
        assertEquals(2, client.logins.size());
    }

    private static final class FakeClock extends Clock {
        Instant now = Instant.ofEpochSecond(1_000);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private static final class FakeScheduler implements AdmissionService.Scheduler {
        final List<Job> jobs = new ArrayList<>();
        public AdmissionService.Cancellation schedule(Duration delay, Runnable task) {
            Job job = new Job(delay, task);
            jobs.add(job);
            return () -> job.cancelled = true;
        }
        long live(Duration delay) {
            return jobs.stream().filter(job -> !job.cancelled && job.delay.equals(delay)).count();
        }
        void fire(Duration delay) {
            Job job = jobs.stream().filter(item -> !item.cancelled && item.delay.equals(delay)).findFirst().orElseThrow();
            job.cancelled = true;
            job.task.run();
        }
        private static final class Job {
            final Duration delay;
            final Runnable task;
            boolean cancelled;
            Job(Duration delay, Runnable task) { this.delay = delay; this.task = task; }
        }
    }

    private static final class Actions implements AdmissionActions {
        java.util.function.Consumer<UUID> onKick = uuid -> {};
        final List<String> messages = new ArrayList<>();
        final Set<UUID> kicked = new HashSet<>();
        int prompts;
        int releases;
        public void prompt(UUID uuid) { prompts++; }
        public void message(UUID uuid, String message) { messages.add(message); }
        public void release(UUID uuid) { releases++; }
        public void kick(UUID uuid, String reason) { kicked.add(uuid); messages.add(reason); onKick.accept(uuid); }
    }

    private static final class FakeClient implements GameAuthClient {
        CompletableFuture<WhitelistResult> whitelist;
        final List<CompletableFuture<CrackedLoginResult>> logins = new ArrayList<>();
        final List<CompletableFuture<SessionCheckResult>> checks = new ArrayList<>();
        final List<String> checkInputs = new ArrayList<>();
        char[] received;
        String receivedNickname;
        boolean throwLogin;
        boolean throwCheck;
        boolean delayedConsumption;
        char[] original;
        boolean originalClearedAtStart;
        boolean consumeMatches(char[] expected) { return Arrays.equals(expected, received); }
        void clearOwnedInput() { Arrays.fill(received, '\0'); }
        public CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] input) {
            if (original != null) {
                originalClearedAtStart = true;
                for (char value : original) originalClearedAtStart &= value == 0;
            }
            received = input;
            receivedNickname = nickname;
            // Retain ownership even when invocation throws; only explicit owner cleanup clears it.
            if (throwLogin) throw new IllegalStateException();
            var future = new CompletableFuture<CrackedLoginResult>();
            future.whenComplete((result, failure) -> {
                if (!delayedConsumption) Arrays.fill(input, '\0');
            });
            logins.add(future);
            return future;
        }
        public CompletionStage<SessionCheckResult> checkSession(String ignored) {
            if (throwCheck) throw new IllegalStateException();
            checkInputs.add(ignored);
            var future = new CompletableFuture<SessionCheckResult>();
            checks.add(future);
            return future;
        }
        public CompletionStage<PolicyResult> fetchPolicy(String nickname) { throw new AssertionError(); }
        public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) {
            if (whitelist == null) throw new AssertionError();
            return whitelist;
        }
        public void close() { throw new AssertionError(); }
    }
}
