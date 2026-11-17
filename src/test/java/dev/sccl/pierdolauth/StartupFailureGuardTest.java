package dev.sccl.pierdolauth;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.sccl.pierdolauth.config.ConfigurationException;
import dev.sccl.pierdolauth.config.PluginConfig;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;

class StartupFailureGuardTest {
    @TempDir Path directory;
    private static final String UNAVAILABLE = "Authentication service is unavailable. Please try again later.";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void failedStartupRetainsDenyOnlyGuardUntilRepeatedShutdown(int stage) throws Exception {
        var guard = new Registration();
        var normal = new PluginLifecycle.ListenerRegistration() {
            public void register() { throw new IllegalStateException("synthetic registration failure"); }
            public void close() {}
        };
        try (var lifecycle = new PluginLifecycle(
                () -> {
                    if (stage == 0) throw new ConfigurationException();
                    return config();
                }, config -> {
                    if (stage == 1) throw new ConfigurationException();
                    return new PluginLifecycleTest.FakeClient();
                }, (client, config, registry) -> {
                    if (stage == 2) throw new IllegalStateException();
                    return new WhitelistSynchronizer(client, new dev.sccl.pierdolauth.whitelist.PaperWhitelist() {
                        public java.util.Set<UUID> currentUuids() { throw new AssertionError("no Paper reads"); }
                        public void replaceWith(java.util.Collection<dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity> ids) {
                            throw new AssertionError("no whitelist mutation");
                        }
                    }, Duration.ofHours(1), task -> {});
                }, (client, config, registry) -> {
                    if (stage == 3) throw new IllegalStateException();
                    return null;
                }, (admission, registry) -> {
                    if (stage == 4) throw new IllegalStateException();
                    return normal;
                }, guard)) {
            assertFalse(lifecycle.enable());
            assertTrue(guard.registered(), "startup failure must retain registered enforcement");
            assertEquals(1, guard.registrations);
            assertNull(lifecycle.registry());
            var player = player();
            assertTrue(player.isWhitelisted(), "synthetic existing Paper membership is not authentication");
            var preLogin = preLogin(player);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());
            dispatch(preLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, preLogin.getLoginResult());
            assertEquals(UNAVAILABLE, preLogin.getKickMessage());
            dispatch(new PlayerJoinEvent(player, (Component) null));
            assertEquals(Component.text(UNAVAILABLE), kicked);
            lifecycle.close();
            assertFalse(guard.registered());
            assertEquals(1, guard.closes);
            lifecycle.close();
            assertEquals(1, guard.closes);
            var after = preLogin(player);
            dispatch(after);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, after.getLoginResult());
        } finally {
            HandlerList.unregisterAll(guard.listener);
        }
    }

    @Test void successfulStartupNeverRegistersFailureGuard() {
        var guard = new Registration();
        try (var lifecycle = new PluginLifecycle(this::config, config -> new PluginLifecycleTest.FakeClient(),
                (client, config, registry) -> new WhitelistSynchronizer(client,
                        new dev.sccl.pierdolauth.whitelist.PaperWhitelist() {
                            public java.util.Set<UUID> currentUuids() { return java.util.Set.of(); }
                            public void replaceWith(java.util.Collection<dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity> ids) {}
                        }, Duration.ofHours(1), task -> {}),
                (client, config, registry) -> null, (admission, registry) -> null, guard)) {
            assertTrue(lifecycle.enable());
            assertEquals(0, guard.registrations);
            assertFalse(guard.registered());
            lifecycle.close();
            assertEquals(0, guard.closes);
        }
    }

    private Component kicked;

    private Player player() {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isWhitelisted" -> true;
                    case "getName" -> "SyntheticPlayer";
                    case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
                    case "kick" -> { kicked = (Component) args[0]; yield null; }
                    default -> throw new AssertionError("Unexpected player access");
                });
    }

    private AsyncPlayerPreLoginEvent preLogin(Player player) {
        return new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(),
                player.getUniqueId(), false, (com.destroystokyo.paper.profile.PlayerProfile)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {com.destroystokyo.paper.profile.PlayerProfile.class},
                        (proxy, method, args) -> null));
    }

    private PluginConfig config() {
        return new PluginConfig("synthetic-server", java.net.URI.create("http://example.invalid/internal/game-auth/v1"),
                directory.resolve("unused-synthetic-credential"), Duration.ofHours(1), Duration.ofSeconds(60), "login");
    }

    private static void dispatch(Event event) throws Exception {
        for (var listener : event.getHandlers().getRegisteredListeners()) listener.callEvent(event);
    }

    private static final class Registration implements PluginLifecycle.ListenerRegistration {
        final StartupFailureGuard listener = new StartupFailureGuard();
        final Plugin plugin = (Plugin) Proxy.newProxyInstance(StartupFailureGuardTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getName" -> "SyntheticPlugin";
                    default -> throw new AssertionError("Unexpected plugin access");
                });
        int registrations;
        int closes;

        public void register() {
            registrations++;
            for (var method : listener.getClass().getDeclaredMethods()) {
                var annotation = method.getAnnotation(EventHandler.class);
                if (annotation == null) continue;
                assertEquals(EventPriority.LOWEST, annotation.priority());
                assertFalse(annotation.ignoreCancelled());
                try {
                    var handlers = (HandlerList) method.getParameterTypes()[0].getMethod("getHandlerList").invoke(null);
                    handlers.register(new RegisteredListener(listener, (target, event) -> {
                        try { method.invoke(target, event); }
                        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }, annotation.priority(), plugin, annotation.ignoreCancelled()));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }
        }

        boolean registered() {
            return List.of(AsyncPlayerPreLoginEvent.getHandlerList(), PlayerJoinEvent.getHandlerList()).stream()
                    .allMatch(handlers -> java.util.Arrays.stream(handlers.getRegisteredListeners())
                            .anyMatch(entry -> entry.getListener() == listener));
        }

        public void close() { closes++; listener.close(); }
    }
}
