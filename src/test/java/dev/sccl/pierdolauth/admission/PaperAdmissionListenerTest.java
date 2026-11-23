package dev.sccl.pierdolauth.admission;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.sccl.pierdolauth.api.GameAuthClient;
import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import dev.sccl.pierdolauth.licensed.FastLoginBridge;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import com.github.games647.fastlogin.bukkit.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.event.BukkitFastLoginPreLoginEvent;
import com.github.games647.fastlogin.bukkit.event.BukkitFastLoginAutoLoginEvent;
import java.lang.reflect.Proxy;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.*;
import org.bukkit.permissions.*;
import org.bukkit.plugin.Plugin;

class PaperAdmissionListenerTest {
    private static final Map<String, Boolean> FASTLOGIN_DENIES = Map.of(
            "fastlogin.bukkit.command.premium", false,
            "fastlogin.bukkit.command.premium.other", false,
            "fastlogin.bukkit.command.cracked", false,
            "fastlogin.bukkit.command.cracked.other", false,
            "fastlogin.bukkit.command.delete", false);
    private final Map<Player, List<PermissionAttachment>> attachments = new IdentityHashMap<>();
    private final Plugin permissionOwner = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                default -> throw new AssertionError("Unexpected Plugin operation: " + method.getName());
            });

    @org.junit.jupiter.api.BeforeAll static void permissionPlatform() {
        var manager = (org.bukkit.plugin.PluginManager) Proxy.newProxyInstance(
                PaperAdmissionListenerTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.plugin.PluginManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDefaultPermissions" -> Set.of();
                    case "getPermission" -> null;
                    case "subscribeToPermission", "unsubscribeFromPermission",
                            "subscribeToDefaultPerms", "unsubscribeFromDefaultPerms" -> null;
                    default -> throw new AssertionError("Unexpected permission platform operation: " + method.getName());
                });
        var server = (org.bukkit.Server) Proxy.newProxyInstance(
                PaperAdmissionListenerTest.class.getClassLoader(), new Class<?>[] {org.bukkit.Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> manager;
                    case "getLogger" -> java.util.logging.Logger.getLogger("permission-test");
                    case "getName", "getVersion", "getBukkitVersion" -> "permission-test";
                    default -> throw new AssertionError("Unexpected Server operation: " + method.getName());
                });
        try {
            org.bukkit.Bukkit.setServer(server);
        } catch (NoSuchElementException missingBuildMetadata) {
            // paper's post-install version log
            assertSame(server, org.bukkit.Bukkit.getServer());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ClientType.class)
    void fastLoginDeniesApplyToPendingAndAuthenticatedPlayersIncludingOperators(ClientType type) {
        registry.publish(List.of(new WhitelistIdentity("Player", type, id)));
        try (var admission = licensedService();
                var adapter = new PaperAdmissionListener(admission, registry, () -> bridgeReady, proofs::add, () -> permissionOwner)) {
            for (boolean op : List.of(false, true)) {
                Player exact = player(); exact.setOp(op);
                adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
                assertFastLoginDenied(exact);
                if (type == ClientType.LICENSED) {
                    assertTrue(adapter.premiumVerified(exact, () -> true));
                    proofs.remove().run();
                } else {
                    adapter.onCommand(command(exact, "/login ignored"));
                    client.pending.complete(new CrackedLoginResult.Allowed(id, "lease", Instant.now().plusSeconds(60).getEpochSecond()));
                    callbacks.remove().run();
                }
                var gameplay = command(exact, "/help"); adapter.onCommand(gameplay);
                assertFalse(gameplay.isCancelled(), "The exact player is authenticated");
                assertFastLoginDenied(exact);
                assertTrue(exact.hasPermission("pierdolauth.login"), "Login permission is untouched");
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void fastLoginCleanupIsExactForQuitExplicitKickAndExternalKick(int cleanup) {
        Player old = join();
        assertFastLoginDenied(old);
        Player replacement = player();
        replacement.setOp(true);
        listener.onJoin(new PlayerJoinEvent(replacement, (net.kyori.adventure.text.Component) null));
        assertTrue(attachments.get(old).isEmpty(), "Replacement removes old exact attachment");
        assertFastLoginDenied(replacement);
        cleanPlayer(old, cleanup);
        assertFastLoginDenied(replacement);
        onKick = () -> assertTrue(attachments.get(replacement).isEmpty(), "Remove before kick dispatch");
        cleanPlayer(replacement, cleanup);
        assertTrue(attachments.get(replacement).isEmpty());
        assertTrue(replacement.hasPermission("fastlogin.bukkit.command.premium"), "No retained deny after cleanup");
    }

    @Test void fastLoginCloseRemovesAllAttachmentsAndSameObjectRejoinDoesNotAccumulate() {
        Player exact = join();
        assertFastLoginDenied(exact);
        PermissionAttachment previous = attachments.get(exact).getFirst();
        listener.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
        assertFastLoginDenied(exact);
        assertNotSame(previous, attachments.get(exact).getFirst());
        Player other = player(UUID.randomUUID());
        listener.onJoin(new PlayerJoinEvent(other, (net.kyori.adventure.text.Component) null));
        assertFastLoginDenied(other);
        listener.close(); listener.close();
        assertTrue(attachments.get(exact).isEmpty());
        assertTrue(attachments.get(other).isEmpty());
        listener.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
        assertTrue(attachments.get(exact).isEmpty());
    }

    private void cleanPlayer(Player player, int cleanup) {
        switch (cleanup) {
            case 0 -> listener.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
            case 1 -> listener.kick(player, "Denied");
            case 2 -> listener.onKick(new PlayerKickEvent(player, net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.text.Component.empty(), PlayerKickEvent.Cause.UNKNOWN));
        }
    }

    private void assertFastLoginDenied(Player player) {
        assertEquals(1, attachments.get(player).size(), "One attachment for the exact player");
        var attachment = attachments.get(player).getFirst();
        assertSame(player, attachment.getPermissible());
        assertEquals(FASTLOGIN_DENIES, attachment.getPermissions());
        for (String node : FASTLOGIN_DENIES.keySet()) {
            assertTrue(player.isPermissionSet(node), node);
            assertFalse(player.hasPermission(node), node);
        }
    }
    @org.junit.jupiter.api.AfterEach void closeResources() {
        listener.close();
        service.close();
        client.close();
    }
    private final UUID id = UUID.randomUUID();
    private final WhitelistRegistry registry = new WhitelistRegistry();
    private final Queue<Runnable> callbacks = new ArrayDeque<>();
    private final Client client = new Client();
    private int prompts;
    private int releases;
    private final List<net.kyori.adventure.text.Component> skinMessages = new ArrayList<>();
    private Runnable onKick = () -> {};
    private boolean bridgeReady = true;
    private final Queue<Runnable> proofs = new ConcurrentLinkedQueue<>();
    private final AdmissionService service = new AdmissionService(client, registry, Duration.ofSeconds(60),
            Clock.systemUTC(), (delay, task) -> () -> {}, callbacks::add, new AdmissionActions() {
                public void prompt(UUID uuid) { prompts++; }
                public void message(UUID uuid, String message) {}
                public void release(UUID uuid) { releases++; }
                public void kick(UUID uuid, String reason) {}
            }, 10);
    private final PaperAdmissionListener listener = new PaperAdmissionListener(service, registry, () -> false,
            Runnable::run, () -> permissionOwner);

    @Test void parserAcceptsOnlyLiteralPrefixAndPreservesRemainder() {
        assertTrue("correct horse".equals(PaperAdmissionListener.parsePassword("/login correct horse").orElseThrow()));
        assertTrue(PaperAdmissionListener.parsePassword("/login").isEmpty());
        assertTrue(PaperAdmissionListener.parsePassword("/Login correct horse").isEmpty());
    }

    @Test void skinGateBlocksAllLabelsUntilCrackedAdmissionAndAfterKick() {
        Player exact = join();
        var labels = List.of("/skin set Player", "/SKINS", "/sR applyskin Player",
                "/SkinsRestorer", "/skinsrestorer:skin url https://example.invalid/skin.png",
                "/SkinsRestorer:SR", "/skinsrestorer:skins", "/skinsrestorer:skinsrestorer");
        for (String label : labels) {
            var event = command(exact, label);
            listener.onSkinCommand(event);
            assertTrue(event.isCancelled(), label);
            assertEquals(label, event.getMessage());
        }
        assertEquals(labels.size(), skinMessages.size());
        assertTrue(skinMessages.stream().allMatch(message ->
                ((net.kyori.adventure.text.TextComponent) message).content().contains("log in")));
        listener.onCommand(command(exact, "/login ignored"));
        client.pending.complete(new CrackedLoginResult.Allowed(id, "lease", Instant.now().plusSeconds(60).getEpochSecond()));
        var queued = command(exact, "/skin"); listener.onSkinCommand(queued);
        assertTrue(queued.isCancelled());
        callbacks.remove().run();
        for (String label : labels) {
            var event = command(exact, label); listener.onSkinCommand(event);
            assertFalse(event.isCancelled(), label);
            event.setCancelled(true); listener.onSkinCommand(event);
            assertTrue(event.isCancelled());
        }
        var missing = command(player(), "/skin"); listener.onSkinCommand(missing);
        assertTrue(missing.isCancelled(), "Equal distinct Player cannot borrow authentication");
        listener.kick(exact, "Denied");
        var kicked = command(exact, "/sr"); listener.onSkinCommand(kicked);
        assertTrue(kicked.isCancelled());
    }

    @Test void skinGateLeavesUnrelatedCommandsAloneAndFailsClosedWithoutState() {
        Player exact = player();
        for (String label : List.of("/login ignored", "/help", "/premium", "/skinny", "/say /skin", "/help https://example.invalid/skin")) {
            var event = command(exact, label); listener.onSkinCommand(event);
            assertFalse(event.isCancelled(), label);
        }
        assertTrue(skinMessages.isEmpty());
        var missing = command(exact, "/sr"); listener.onSkinCommand(missing);
        assertTrue(missing.isCancelled());
        listener.close();
        var closed = command(exact, "/skins"); listener.onSkinCommand(closed);
        assertTrue(closed.isCancelled());
    }

    @Test void skinGateWaitsForLicensedProofToBeReleased() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        try (var licensed = licensedService();
                var adapter = new PaperAdmissionListener(licensed, registry, () -> bridgeReady, proofs::add, () -> permissionOwner)) {
            Player exact = player();
            adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
            assertTrue(adapter.premiumVerified(exact, () -> true));
            var before = command(exact, "/skin"); adapter.onSkinCommand(before);
            assertTrue(before.isCancelled());
            proofs.remove().run();
            var after = command(exact, "/skin"); adapter.onSkinCommand(after);
            assertFalse(after.isCancelled());
            adapter.onQuit(new PlayerQuitEvent(exact, (net.kyori.adventure.text.Component) null));
            var quit = command(exact, "/skin"); adapter.onSkinCommand(quit);
            assertTrue(quit.isCancelled());
        }
    }

    @Test void commandIsRedactedAndCancelledBeforeApiInvocation() {
        Player player = join();
        String input = UUID.randomUUID().toString();
        var event = command(player, "/login " + input);
        client.onLogin = () -> {
            assertTrue(event.isCancelled());
            assertEquals("/login", event.getMessage());
        };
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> listener.onCommand(event));
        assertEquals(1, client.calls);
        assertTrue(client.matches(input), "Input must be forwarded unchanged");
        listener.onCommand(command(player, "/login " + input));
        assertEquals(1, client.calls);
    }

    @Test void otherCommandsAreCancelledWithoutApiAndIncompleteLoginPrompts() {
        Player player = join();
        for (String message : List.of("/help", "/Login", "/minecraft:login", "/login", "/login ")) {
            var event = command(player, message);
            listener.onCommand(event);
            assertTrue(event.isCancelled());
        }
        assertEquals(0, client.calls);
        assertEquals(3, prompts);
    }

    @Test void missingTokenCancelsAndRedactsWithoutApi() {
        var event = command(player(), "/login " + UUID.randomUUID());
        listener.onCommand(event);
        assertTrue(event.isCancelled());
        assertEquals("/login", event.getMessage());
        assertEquals(0, client.calls);
    }

    @Test void helperFailsClosedAndPreservesExistingCancellation() throws Exception {
        var token = service.join(new ConnectionIdentity(id, "Player", InetAddress.getLoopbackAddress()));
        Flag missing = new Flag();
        listener.cancelIfFrozen(null, missing);
        assertTrue(missing.cancelled);
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id)));
        token = service.join(new ConnectionIdentity(id, "Player", InetAddress.getLoopbackAddress()));
        Flag frozen = new Flag();
        listener.cancelIfFrozen(token, frozen);
        assertTrue(frozen.cancelled);
        service.command(token, UUID.randomUUID().toString().toCharArray());
        client.pending.complete(new CrackedLoginResult.Allowed(id, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(300).getEpochSecond()));
        callbacks.remove().run();
        Flag allowed = new Flag();
        listener.cancelIfFrozen(token, allowed);
        assertFalse(allowed.cancelled);
        listener.cancelIfFrozen(token, frozen);
        assertTrue(frozen.cancelled);
    }

    @Test void staleQuitCannotRemoveReplacementObjectEvenWhenPlayersCompareEqual() {
        Player old = join();
        Player replacement = player();
        listener.onJoin(new PlayerJoinEvent(replacement, (net.kyori.adventure.text.Component) null));
        listener.onQuit(new PlayerQuitEvent(old, (net.kyori.adventure.text.Component) null));
        var event = command(replacement, "/login " + UUID.randomUUID());
        listener.onCommand(event);
        assertEquals(1, client.calls);
        assertTrue(event.isCancelled());
        var stale = command(old, "/help");
        listener.onCommand(stale);
        assertTrue(stale.isCancelled());
    }

    @Test void preLoginOnlyReadsRegistryAndPreservesPaperDenials() {
        var event = preLogin();
        listener.onPreLogin(event);
        assertNotEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
        registry.publish(List.of());
        event = preLogin(); listener.onPreLogin(event);
        assertNotEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        event = preLogin(); listener.onPreLogin(event);
        assertEquals("Authentication service is unavailable. Please try again later.", event.getKickMessage());
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id)));
        event = preLogin(); listener.onPreLogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "Denied");
        listener.onPreLogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        assertEquals(0, client.calls);
    }

    @Test void preExistingDenialIsPreservedEvenForUnreadyOrLicensedIdentity() {
        var event = preLogin();
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "Paper denial");
        listener.onPreLogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        assertEquals("Paper denial", event.getKickMessage());
    }

    @Test void kickingExactPlayerInvalidatesServiceTokenBeforeCancelledKick() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id)));
        var token = service.join(new ConnectionIdentity(id, "Player", InetAddress.getLoopbackAddress()));
        // join the exact adapter object, replacing the direct service connection.
        Player player = player();
        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        listener.kick(player, "Denied");
        var replacement = service.join(new ConnectionIdentity(id, "Player", InetAddress.getLoopbackAddress()));
        assertFalse(service.frozen(token));
        // kicking a stale object must never discard the current token by UUID.
        listener.kick(player, "Denied again");
        assertTrue(service.frozen(replacement));
    }

    @Test void cancelledKickCannotLeavePendingLoginAbleToReleaseServiceAuthority() {
        Player player = join();
        listener.onCommand(command(player, "/login ignored"));
        listener.kick(player, "Denied");
        client.pending.complete(new CrackedLoginResult.Allowed(id, "lease", Instant.now().plusSeconds(60).getEpochSecond()));
        callbacks.remove().run();
        assertEquals(0, releases);
    }

    private AdmissionService licensedService() {
        return new AdmissionService(client, registry, Duration.ofSeconds(60), Clock.systemUTC(),
                (delay, task) -> () -> {}, callbacks::add, new AdmissionActions() {
                    public void prompt(UUID uuid) { prompts++; }
                    public void message(UUID uuid, String message) {}
                    public void release(UUID uuid) { releases++; }
                    public void kick(UUID uuid, String reason) {}
                }, 10, () -> bridgeReady);
    }

    private static final class BridgePlatform implements FastLoginBridge.Platform {
        boolean enabled = true;
        boolean initialized;
        boolean registered;
        boolean cancelled;
        boolean failRegistration;
        AuthPlugin<Player> hook;
        Runnable refresh;
        public boolean enabled() { return enabled; }
        public boolean initialized() { return initialized; }
        public AuthPlugin<Player> hook() { return hook; }
        public void hook(AuthPlugin<Player> value) { hook = value; }
        public void register(org.bukkit.event.Listener listener) {
            registered = true;
            if (failRegistration) throw new IllegalStateException("registration failure");
        }
        public AdmissionService.Cancellation monitor(Runnable refresh) {
            this.refresh = refresh;
            return () -> cancelled = true;
        }
    }

    @Test void typedBridgeInstallsBeforeInitializationRefreshesReadinessAndOwnsHookCleanup() {
        BridgePlatform platform = new BridgePlatform();
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            assertSame(bridge, platform.hook);
            assertTrue(platform.registered);
            assertFalse(bridge.ready());
            platform.initialized = true; platform.refresh.run();
            assertTrue(bridge.ready());
            assertTrue(bridge.isRegistered("unknown"));
            assertFalse(bridge.forceRegister(player(), "ignored"));
            assertEquals(0, client.calls);
            bridge.close();
            assertFalse(bridge.ready());
            assertNull(platform.hook);
            assertTrue(platform.cancelled);
            platform.refresh.run();
            assertFalse(bridge.ready());
        }
    }

    @Test void typedBridgeDoesNotOverwriteUnrelatedHookOrAttachDisabledOrLateDependency() {
        for (int scenario = 0; scenario < 3; scenario++) {
            BridgePlatform platform = new BridgePlatform();
            AuthPlugin<Player> unrelated = new AuthPlugin<>() {
                public boolean forceLogin(Player p) { return false; }
                public boolean forceRegister(Player p, String input) { return false; }
                public boolean isRegistered(String name) { return false; }
            };
            if (scenario == 0) platform.hook = unrelated;
            if (scenario == 1) platform.enabled = false;
            if (scenario == 2) platform.initialized = true;
            try (var bridge = new FastLoginBridge(platform, registry, listener)) {
                assertFalse(bridge.ready());
                assertFalse(platform.registered);
                assertFalse(bridge.forceLogin(player()));
                assertSame(scenario == 0 ? unrelated : null, platform.hook);
            }
        }
    }

    @Test void partialBridgeRegistrationFailureLeavesNoHookAndCannotAuthorize() {
        BridgePlatform platform = new BridgePlatform(); platform.failRegistration = true;
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            assertFalse(bridge.ready());
            assertNull(platform.hook);
            assertFalse(bridge.forceLogin(player()));
        }
    }

    @Test void bridgeCloseCannotDetachAnotherOwnersReplacementHook() {
        BridgePlatform platform = new BridgePlatform();
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            assertNotNull(platform.refresh);
            platform.initialized = true; platform.refresh.run();
            assertTrue(bridge.ready());
            AuthPlugin<Player> unrelated = new AuthPlugin<>() {
                public boolean forceLogin(Player p) { return false; }
                public boolean forceRegister(Player p, String input) { return false; }
                public boolean isRegistered(String name) { return true; }
            };
            platform.hook = unrelated; platform.refresh.run();
            assertFalse(bridge.ready()); bridge.close();
            assertSame(unrelated, platform.hook);
        }
    }

    @Test void unknownRegistryAndUnverifiedSessionCannotUseProfilePreferenceAsAuthority() {
        BridgePlatform platform = new BridgePlatform();
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            platform.initialized = true; platform.refresh.run();
            var profile = new StoredProfile(id, "Player", true, "");
            profile.setOnlinemodePreferred(true);
            bridge.onPreLogin(new BukkitFastLoginPreLoginEvent("Player", null, profile));
            assertFalse(profile.isOnlinemodePreferred());
            var session = new BukkitLoginSession("Player", false, profile);
            session.setUuid(id); session.setVerifiedPremium(true);
            var event = new BukkitFastLoginAutoLoginEvent(session, profile);
            bridge.onAutoLogin(event);
            assertTrue(event.isCancelled());
            assertEquals(0, releases);
        }
    }

    @Test void transientProfileIsImmediatelyDowngradedUnlessCurrentReadyLicensedName() {
        BridgePlatform platform = new BridgePlatform();
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            platform.initialized = true;
            // explicit refresh also proves initial unready state is not permanent
            assertNotNull(platform.refresh); platform.refresh.run();
            for (int scenario = 0; scenario < 5; scenario++) {
                registry.publish(List.of(new WhitelistIdentity("Player", scenario == 1 ? ClientType.CRACKED : ClientType.LICENSED, id)));
                platform.enabled = scenario != 2; platform.refresh.run();
                var profile = new StoredProfile(id, "Player", true, "");
                profile.setOnlinemodePreferred(true);
                bridge.onPreLogin(new BukkitFastLoginPreLoginEvent(scenario == 0 ? "Unknown" : "pLaYeR", null, profile));
                assertEquals(scenario >= 3, profile.isOnlinemodePreferred(), "scenario " + scenario);
            }
            assertEquals(0, releases);
        }
    }

    @Test void typedAutologinOnlyVetoesAndNeverReleasesOrUndoesCancellation() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        BridgePlatform platform = new BridgePlatform();
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            assertNotNull(platform.refresh);
            platform.initialized = true; platform.refresh.run();
            for (int scenario = 0; scenario < 6; scenario++) {
                var profile = new StoredProfile(id, "Player", true, "");
                var session = new BukkitLoginSession("Player", false, profile);
                session.setUuid(scenario == 1 ? UUID.randomUUID() : id);
                session.setVerifiedPremium(scenario != 0);
                if (scenario == 2) session.setVerifiedUsername("Other");
                var event = new BukkitFastLoginAutoLoginEvent(session, profile);
                if (scenario == 3) event.setCancelled(true);
                platform.enabled = scenario != 4; platform.refresh.run();
                bridge.onAutoLogin(event);
                assertEquals(scenario != 5, event.isCancelled(), "scenario " + scenario);
                assertEquals(0, releases);
                assertTrue(proofs.isEmpty());
            }
        }
    }

    @Test void missingTypedSessionIsVetoedWithoutThrowingOrReleasing() {
        BridgePlatform platform = new BridgePlatform();
        try (var bridge = new FastLoginBridge(platform, registry, listener)) {
            platform.initialized = true; platform.refresh.run();
            var event = new BukkitFastLoginAutoLoginEvent(null, new StoredProfile(id, "Player", true, ""));
            assertDoesNotThrow(() -> bridge.onAutoLogin(event));
            assertTrue(event.isCancelled());
            assertEquals(0, releases);
        }
    }

    @Test void typedForceLoginReleasesOnlyAfterExactMainThreadVerification() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        BridgePlatform platform = new BridgePlatform();
        try (var licensed = licensedService();
                var adapter = new PaperAdmissionListener(licensed, registry, () -> bridgeReady, proofs::add, () -> permissionOwner);
                var bridge = new FastLoginBridge(platform, registry, adapter)) {
            platform.initialized = true; platform.refresh.run();
            Player exact = player(); adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
            assertTrue(bridge.forceLogin(exact));
            assertEquals(0, releases);
            proofs.remove().run();
            assertEquals(1, releases);
            var gameplay = command(exact, "/help"); adapter.onCommand(gameplay);
            assertFalse(gameplay.isCancelled());
            assertEquals(0, client.calls);
            assertEquals(0, prompts);
        }
    }

    @Test void bridgeForceLoginQueuesOnlyExactPlayerAndCloseInvalidatesAcceptedWork() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        BridgePlatform platform = new BridgePlatform();
        try (var licensed = licensedService();
                var adapter = new PaperAdmissionListener(licensed, registry, () -> bridgeReady, proofs::add, () -> permissionOwner);
                var bridge = new FastLoginBridge(platform, registry, adapter)) {
            assertNotNull(platform.refresh);
            platform.initialized = true; platform.refresh.run();
            Player exact = player(); adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
            assertFalse(bridge.forceLogin(player()));
            assertTrue(bridge.forceLogin(exact));
            assertEquals(0, releases);
            bridge.close(); proofs.remove().run();
            assertEquals(0, releases);
        }
    }

    @Test void exactLicensedPlayerProofIsQueuedThenReleasesGameplayWithoutLogin() throws Exception {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        try (var licensed = licensedService();
                var adapter = new PaperAdmissionListener(licensed, registry, () -> bridgeReady, proofs::add, () -> permissionOwner)) {
            Player exact = player();
            adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
            var before = command(exact, "/help"); adapter.onCommand(before);
            assertTrue(before.isCancelled());
            assertFalse(adapter.premiumVerified(player(), () -> true), "Equal distinct object cannot borrow token");
            assertTrue(CompletableFuture.supplyAsync(() -> adapter.premiumVerified(exact, () -> true)).join());
            assertEquals(0, releases);
            proofs.remove().run();
            assertEquals(1, releases);
            var after = command(exact, "/help"); adapter.onCommand(after);
            assertFalse(after.isCancelled());
            assertEquals(0, client.calls);
            assertEquals(0, prompts);
        }
    }

    @Test void proofReadinessIsRecheckedAfterQueueAcceptance() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        try (var licensed = licensedService();
                var adapter = new PaperAdmissionListener(licensed, registry, () -> bridgeReady, proofs::add, () -> permissionOwner)) {
            Player exact = player();
            adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
            boolean[] open = {true};
            assertTrue(adapter.premiumVerified(exact, () -> open[0]));
            open[0] = false;
            proofs.remove().run();
            assertEquals(0, releases);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void queuedProofCannotBorrowReplacementOrSurviveCloseReadinessQuitKick(int change) {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        try (var licensed = licensedService();
                var adapter = new PaperAdmissionListener(licensed, registry, () -> bridgeReady, proofs::add, () -> permissionOwner)) {
            Player exact = player();
            adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
            assertTrue(adapter.premiumVerified(exact, () -> true));
            switch (change) {
                case 0 -> adapter.onJoin(new PlayerJoinEvent(player(), (net.kyori.adventure.text.Component) null));
                case 1 -> adapter.onJoin(new PlayerJoinEvent(exact, (net.kyori.adventure.text.Component) null));
                case 2 -> adapter.close();
                case 3 -> bridgeReady = false;
                case 4 -> adapter.onQuit(new PlayerQuitEvent(exact, (net.kyori.adventure.text.Component) null));
                case 5 -> adapter.onKick(new PlayerKickEvent(exact, net.kyori.adventure.text.Component.empty(),
                        net.kyori.adventure.text.Component.empty(), PlayerKickEvent.Cause.UNKNOWN));
            }
            proofs.remove().run();
            assertEquals(0, releases);
            assertEquals(0, client.calls);
        }
    }

    @Test void readyLicensedPreLoginPassesWithoutOverridingPriorDenial() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.LICENSED, id)));
        try (var adapter = new PaperAdmissionListener(service, registry, () -> bridgeReady, proofs::add, () -> permissionOwner)) {
            var event = preLogin(); adapter.onPreLogin(event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "Paper ban");
            bridgeReady = false; adapter.onPreLogin(event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
            assertEquals("Paper ban", event.getKickMessage());
            var unavailable = preLogin(); adapter.onPreLogin(unavailable);
            assertEquals("Authentication service is unavailable. Please try again later.", unavailable.getKickMessage());
        }
    }

    @Test void closedListenerSuppressesJoinsAndFailsClosedCommands() {
        Player player = join();
        listener.close();
        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        var event = command(player, "/login " + UUID.randomUUID());
        listener.onCommand(event);
        assertTrue(event.isCancelled());
        assertEquals(0, client.calls);
    }

    @Test void everyGameplayHandlerCancelsFrozenAndMissingButPreservesAuthenticated() throws Exception {
        Player player = join();
        exerciseGameplay(player, true);
        exerciseGameplay(player(), true);
        listener.onCommand(command(player, "/login " + UUID.randomUUID()));
        client.pending.complete(new CrackedLoginResult.Allowed(id, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(300).getEpochSecond()));
        callbacks.remove().run();
        exerciseGameplay(player, false);
        var command = command(player, "/help");
        listener.onCommand(command);
        assertFalse(command.isCancelled());
    }

    private void exerciseGameplay(Player player, boolean cancelled) throws Exception {
        String[] types = {"player.PlayerMoveEvent", "player.PlayerTeleportEvent", "block.BlockBreakEvent",
                "block.BlockPlaceEvent", "player.PlayerInteractEvent", "player.PlayerInteractEntityEvent",
                "player.PlayerInteractAtEntityEvent", "entity.EntityDamageEvent", "entity.EntityDamageByEntityEvent",
                "inventory.InventoryClickEvent", "inventory.InventoryDragEvent", "inventory.InventoryOpenEvent",
                "entity.EntityPickupItemEvent", "player.PlayerDropItemEvent", "player.PlayerSwapHandItemsEvent",
                "player.PlayerItemHeldEvent", "vehicle.VehicleEnterEvent", "vehicle.VehicleExitEvent",
                "player.PlayerPortalEvent", "player.AsyncPlayerChatEvent", "player.PlayerItemConsumeEvent",
                "player.PlayerBucketEmptyEvent", "player.PlayerBucketFillEvent", "player.PlayerFishEvent",
                "player.PlayerEditBookEvent"};
        for (String type : types) {
            Class<?> eventType = Class.forName("org.bukkit.event." + type);
            Cancellable event = (Cancellable) event(eventType, player);
            var handler = Arrays.stream(PaperAdmissionListener.class.getDeclaredMethods())
                    .filter(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == eventType)
                    .findFirst().orElseThrow(() -> new AssertionError("Missing public event gate: " + type));
            assertEquals(org.bukkit.event.EventPriority.LOWEST,
                    handler.getAnnotation(org.bukkit.event.EventHandler.class).priority());
            handler.invoke(listener, event);
            assertEquals(cancelled, event.isCancelled(), type);
            event.setCancelled(true);
            handler.invoke(listener, event);
            assertTrue(event.isCancelled(), "Gate must not undo prior cancellation");
        }
        var modern = (io.papermc.paper.event.player.AsyncChatEvent) event(
                io.papermc.paper.event.player.AsyncChatEvent.class, player);
        listener.onChat(modern);
        assertEquals(cancelled, modern.isCancelled());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(classes = {
            PlayerToggleSneakEvent.class, PlayerToggleSprintEvent.class, PlayerToggleFlightEvent.class,
            org.bukkit.event.entity.EntityToggleGlideEvent.class})
    void stateTransitionsRequireExactPlayerTokenAndPreserveCancellation(Class<?> type) throws Exception {
        var handler = stateTransitionHandler(type);
        Player exact = join();
        for (Player player : List.of(exact, player())) {
            exerciseStateTransition(handler, type, player, true);
        }
        listener.onCommand(command(exact, "/login " + UUID.randomUUID()));
        client.pending.complete(new CrackedLoginResult.Allowed(id, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(300).getEpochSecond()));
        callbacks.remove().run();
        exerciseStateTransition(handler, type, exact, false);
        exerciseStateTransition(handler, type, player(), true);
    }

    @Test void glideGateDoesNotCancelNonPlayerEntitiesOrClearTheirCancellation() throws Exception {
        var type = org.bukkit.event.entity.EntityToggleGlideEvent.class;
        var handler = stateTransitionHandler(type);
        var entity = (org.bukkit.entity.LivingEntity) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {org.bukkit.entity.LivingEntity.class}, (proxy, method, args) -> null);
        for (boolean cancelled : List.of(false, true)) {
            var event = new org.bukkit.event.entity.EntityToggleGlideEvent(entity, true);
            event.setCancelled(cancelled);
            handler.invoke(listener, event);
            assertEquals(cancelled, event.isCancelled());
        }
    }

    private java.lang.reflect.Method stateTransitionHandler(Class<?> type) {
        var handler = Arrays.stream(PaperAdmissionListener.class.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == type)
                .findFirst().orElseThrow(() -> new AssertionError("Missing state-transition gate: " + type.getSimpleName()));
        var annotation = handler.getAnnotation(org.bukkit.event.EventHandler.class);
        assertNotNull(annotation);
        assertEquals(org.bukkit.event.EventPriority.LOWEST, annotation.priority());
        assertFalse(annotation.ignoreCancelled());
        return handler;
    }

    private void exerciseStateTransition(java.lang.reflect.Method handler, Class<?> type,
            Player player, boolean cancelled) throws Exception {
        for (boolean initial : List.of(false, true)) {
            var event = (Cancellable) event(type, player);
            event.setCancelled(initial);
            handler.invoke(listener, event);
            assertEquals(initial || cancelled, event.isCancelled(), type.getSimpleName());
        }
    }

    @Test void bookEditingAndSigningRequireExactPlayerTokenAndPreserveCancellation() throws Exception {
        var handler = Arrays.stream(PaperAdmissionListener.class.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == PlayerEditBookEvent.class)
                .findFirst().orElseThrow(() -> new AssertionError("Missing public book-edit gate"));
        Player player = join();
        var meta = (org.bukkit.inventory.meta.BookMeta) argument(org.bukkit.inventory.meta.BookMeta.class, player);
        for (boolean signing : List.of(false, true)) {
            var frozen = new PlayerEditBookEvent(player, 0, meta, meta, signing);
            handler.invoke(listener, frozen);
            assertTrue(frozen.isCancelled(), "Frozen players cannot edit or sign books");
        }
        listener.onCommand(command(player, "/login " + UUID.randomUUID()));
        client.pending.complete(new CrackedLoginResult.Allowed(id, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(300).getEpochSecond()));
        callbacks.remove().run();
        for (boolean signing : List.of(false, true)) {
            var allowed = new PlayerEditBookEvent(player, 0, meta, meta, signing);
            handler.invoke(listener, allowed);
            assertFalse(allowed.isCancelled(), "Authenticated exact Player may edit or sign books");
            allowed.setCancelled(true);
            handler.invoke(listener, allowed);
            assertTrue(allowed.isCancelled(), "Authentication must not undo prior cancellation");
            var missing = new PlayerEditBookEvent(player(), 0, meta, meta, signing);
            handler.invoke(listener, missing);
            assertTrue(missing.isCancelled(), "Equal Player with same UUID must not borrow authenticated token");
        }
    }

    @Test void damageIsCancelledForEitherPlayerDirectionAndProjectileShooter() {
        Player frozen = join();
        var other = (org.bukkit.entity.Entity) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Entity.class}, (proxy, method, args) -> null);
        var projectile = (org.bukkit.entity.Projectile) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Projectile.class}, (proxy, method, args) ->
                        method.getName().equals("getShooter") ? frozen : null);
        for (var pair : List.of(new org.bukkit.entity.Entity[] {frozen, other},
                new org.bukkit.entity.Entity[] {other, frozen}, new org.bukkit.entity.Entity[] {projectile, other})) {
            var event = new org.bukkit.event.entity.EntityDamageByEntityEvent(pair[0], pair[1],
                    org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    (org.bukkit.damage.DamageSource) argument(org.bukkit.damage.DamageSource.class, frozen), 1d);
            listener.onDamageByEntity(event);
            assertTrue(event.isCancelled());
        }
    }

    @Test void kickDiscardsTokenBeforePaperCanCancelKickWithoutQuit() {
        Player player = join();
        listener.onCommand(command(player, "/login " + UUID.randomUUID()));
        client.pending.complete(new CrackedLoginResult.Allowed(id, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(300).getEpochSecond()));
        callbacks.remove().run();
        var before = command(player, "/help");
        listener.onCommand(before);
        assertFalse(before.isCancelled());
        onKick = () -> {
            var during = command(player, "/help");
            listener.onCommand(during);
            assertTrue(during.isCancelled(), "Token must be discarded before kick dispatch");
        };
        listener.kick(player, "Denied");
        var after = command(player, "/help");
        listener.onCommand(after);
        assertTrue(after.isCancelled(), "A cancelled kick without quit must stay fail-closed");
        listener.onCommand(command(player, "/login " + UUID.randomUUID()));
        assertEquals(1, client.calls);
    }

    private Object event(Class<?> type, Player player) throws Exception {
        var constructors = new ArrayList<>(List.of(type.getConstructors()));
        constructors.sort(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount));
        for (var constructor : constructors) {
            try {
                Object[] args = Arrays.stream(constructor.getParameterTypes()).map(t -> argument(t, player)).toArray();
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Paper versions expose several public constructor forms; use a compatible one.
            }
        }
        throw new AssertionError("Cannot build event double: " + type.getSimpleName());
    }

    private Object argument(Class<?> type, Player player) {
        if (type.isInstance(player)) return player;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == String.class) return "Message";
        if (type == UUID.class) return id;
        if (type == net.kyori.adventure.text.Component.class) return net.kyori.adventure.text.Component.empty();
        if (type == org.bukkit.Location.class) return new org.bukkit.Location(null, 0, 0, 0);
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (type == Set.class) return new HashSet<>();
        if (type == Map.class) return Map.of();
        if (type.isInterface()) return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPlayer")) return player;
                    if (method.getName().equals("getViewers")) return List.of(player);
                    if (method.getName().equals("getRawSlot")) return 0;
                    return argument(method.getReturnType(), player);
                });
        return null;
    }

    private AsyncPlayerPreLoginEvent preLogin() {
        return new AsyncPlayerPreLoginEvent("Player", InetAddress.getLoopbackAddress(), id, false,
                (com.destroystokyo.paper.profile.PlayerProfile) argument(com.destroystokyo.paper.profile.PlayerProfile.class, player()));
    }

    private PlayerCommandPreprocessEvent command(Player player, String message) {
        return new PlayerCommandPreprocessEvent(player, message, new HashSet<>());
    }

    private Player join() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id)));
        Player player = player();
        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        return player;
    }

    private Player player() {
        return player(id);
    }

    private Player player(UUID uuid) {
        var owned = new ArrayList<PermissionAttachment>();
        boolean[] op = {false};
        PermissibleBase[] permissions = new PermissibleBase[1];
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOp" -> op[0];
                    case "setOp" -> { op[0] = (boolean) args[0]; permissions[0].recalculatePermissions(); yield null; }
                    case "addAttachment" -> {
                        var attachment = permissions[0].addAttachment((Plugin) args[0]);
                        owned.add(attachment); yield attachment;
                    }
                    case "removeAttachment" -> {
                        assertTrue(owned.remove((PermissionAttachment) args[0]), "Only the owning exact player may remove");
                        permissions[0].removeAttachment((PermissionAttachment) args[0]); yield null;
                    }
                    case "recalculatePermissions" -> { permissions[0].recalculatePermissions(); yield null; }
                    case "isPermissionSet" -> permissions[0].isPermissionSet((String) args[0]);
                    case "hasPermission" -> args[0].equals("pierdolauth.login")
                            || permissions[0].hasPermission((String) args[0]);
                    case "getName" -> "Player";
                    case "isOnline" -> true;
                    case "getAddress" -> new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
                    case "equals" -> args[0] instanceof Player;
                    case "hashCode" -> 1;
                    case "toString" -> "Player double";
                    case "kick" -> { onKick.run(); yield null; }
                    case "sendMessage" -> { skinMessages.add((net.kyori.adventure.text.Component) args[0]); yield null; }
                    default -> throw new AssertionError("Unexpected Player operation");
                });
        permissions[0] = new PermissibleBase(player);
        attachments.put(player, owned);
        return player;
    }

    private static final class Flag implements Cancellable {
        boolean cancelled;
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean value) { cancelled = value; }
    }

    private static final class Client implements GameAuthClient {
        int calls;
        char[] received;
        Runnable onLogin = () -> {};
        final CompletableFuture<CrackedLoginResult> pending = new CompletableFuture<>();
        public CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] input) {
            assertEquals("Player", nickname);
            onLogin.run(); calls++; received = input;
            pending.whenComplete((result, failure) -> Arrays.fill(input, '\0'));
            return pending;
        }
        boolean matches(String expected) { return Arrays.equals(expected.toCharArray(), received); }
        public CompletionStage<SessionCheckResult> checkSession(String input) { throw new AssertionError(); }
        public CompletionStage<PolicyResult> fetchPolicy(String nickname) { throw new AssertionError(); }
        public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) { throw new AssertionError(); }
        public void close() { if (received != null) Arrays.fill(received, '\0'); }
    }
}
