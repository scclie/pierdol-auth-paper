package dev.sccl.pierdolauth.licensed;

import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import com.github.games647.fastlogin.bukkit.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.event.BukkitFastLoginPreLoginEvent;
import com.github.games647.fastlogin.bukkit.event.BukkitFastLoginAutoLoginEvent;
import dev.sccl.pierdolauth.admission.AdmissionService;
import dev.sccl.pierdolauth.admission.PaperAdmissionListener;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginDisableEvent;
import dev.sccl.pierdolauth.api.GameAuthModels.ClientType;
import java.util.Locale;

/** Optional typed integration; profile preferences never constitute admission proof. */
public final class FastLoginBridge implements AuthPlugin<Player>, Listener, AutoCloseable {
    public interface Platform {
        boolean enabled();
        boolean initialized();
        AuthPlugin<Player> hook();
        void hook(AuthPlugin<Player> hook);
        void register(Listener listener);
        AdmissionService.Cancellation monitor(Runnable refresh);
    }

    private final Platform platform;
    private final WhitelistRegistry registry;
    private final PaperAdmissionListener listener;
    private AdmissionService.Cancellation monitor;
    private volatile boolean ready;
    private volatile boolean closed;

    /** Construct/close/refresh on Paper's main thread; async readers see only published readiness. */
    public FastLoginBridge(Platform platform, WhitelistRegistry registry, PaperAdmissionListener listener) {
        this.platform = platform;
        this.registry = registry;
        this.listener = listener;
        try {
            // unsupported hot enable and unrelated auth hooks stay fail closed
            if (!platform.enabled() || platform.initialized() || platform.hook() != null) {
                closed = true;
                return;
            }
            platform.register(this);
            platform.hook(this);
            monitor = platform.monitor(this::refresh);
            refresh();
        } catch (RuntimeException | LinkageError ignored) {
            close();
        }
    }

    private void refresh() {
        if (closed) return;
        try {
            ready = platform.enabled() && platform.hook() == this && platform.initialized();
        } catch (RuntimeException | LinkageError ignored) {
            close();
        }
    }

    public boolean ready() { return ready && !closed; }
    public boolean isRegistered(String nickname) { return true; }
    public boolean forceRegister(Player player, String password) { return false; }

    /** True means queued/accepted, never that admission has already been released. */
    public boolean forceLogin(Player player) {
        return ready() && listener.premiumVerified(player, this::ready);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(BukkitFastLoginPreLoginEvent event) {
        var snapshot = registry.snapshot();
        var identity = snapshot.identity(event.getUsername());
        if (!ready() || !snapshot.ready() || identity == null || identity.clientType() != ClientType.LICENSED) {
            // handshake preference is transient, sync to this asyn event
            event.getProfile().setOnlinemodePreferred(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAutoLogin(BukkitFastLoginAutoLoginEvent event) {
        if (event.isCancelled()) return;
        var snapshot = registry.snapshot();
        var session = event.getSession();
        var identity = session == null || session.getUuid() == null ? null : snapshot.identity(session.getUuid());
        if (!ready() || !snapshot.ready() || !(session instanceof BukkitLoginSession bukkit)
                || !bukkit.isVerifiedPremium() || identity == null || identity.clientType() != ClientType.LICENSED
                || !sameName(identity.nickname(), session.getUsername())
                || !sameName(identity.nickname(), session.getRequestUsername())) {
            event.setCancelled(true);
        }
        // this event has no player; only the exact player passed to forceLogin can request release.
    }

    private static boolean sameName(String first, String second) {
        return second != null && first.toLowerCase(Locale.ROOT).equals(second.toLowerCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDependencyDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("FastLogin")) close();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        try {
            if (monitor != null) monitor.cancel();
        } finally {
            HandlerList.unregisterAll(this);
            try {
                if (platform.hook() == this) platform.hook(null);
            } catch (RuntimeException | LinkageError ignored) {
                // invalidate authority if the optional dependency is disabled or incompatible.
            }
        }
    }
}
