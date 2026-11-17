package dev.sccl.pierdolauth;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** deny-only enforcement when normal authentication startup could not complete */
public final class StartupFailureGuard implements Listener, AutoCloseable {
    private static final String UNAVAILABLE = "Authentication service is unavailable. Please try again later.";

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, UNAVAILABLE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().kick(Component.text(UNAVAILABLE));
    }

    @Override public void close() {
        HandlerList.unregisterAll(this);
    }
}
