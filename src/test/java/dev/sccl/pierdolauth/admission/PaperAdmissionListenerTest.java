package dev.sccl.pierdolauth.admission;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.sccl.pierdolauth.api.GameAuthClient;
import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import java.lang.reflect.Proxy;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.*;

class PaperAdmissionListenerTest {
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
    private Runnable onKick = () -> {};
    private final AdmissionService service = new AdmissionService(client, registry, Duration.ofSeconds(60),
            Clock.systemUTC(), (delay, task) -> () -> {}, callbacks::add, new AdmissionActions() {
                public void prompt(UUID uuid) { prompts++; }
                public void message(UUID uuid, String message) {}
                public void release(UUID uuid) {}
                public void kick(UUID uuid, String reason) {}
            }, 10);
    private final PaperAdmissionListener listener = new PaperAdmissionListener(service, registry);

    @Test void parserAcceptsOnlyLiteralPrefixAndPreservesRemainder() {
        assertTrue("correct horse".equals(PaperAdmissionListener.parsePassword("/login correct horse").orElseThrow()));
        assertTrue(PaperAdmissionListener.parsePassword("/login").isEmpty());
        assertTrue(PaperAdmissionListener.parsePassword("/Login correct horse").isEmpty());
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
        assertEquals("Premium authentication is not available yet.", event.getKickMessage());
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, id)));
        event = preLogin(); listener.onPreLogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "Denied");
        listener.onPreLogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        assertEquals(0, client.calls);
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
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Player";
                    case "getAddress" -> new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
                    case "equals" -> args[0] instanceof Player;
                    case "hashCode" -> 1;
                    case "toString" -> "Player double";
                    case "kick" -> { onKick.run(); yield null; }
                    default -> throw new AssertionError("Unexpected Player operation");
                });
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
