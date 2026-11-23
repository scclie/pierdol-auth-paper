package dev.sccl.pierdolauth;

import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import dev.sccl.pierdolauth.api.HttpGameAuthClient;
import dev.sccl.pierdolauth.api.AuthenticatedPlayerService;
import dev.sccl.pierdolauth.admission.AdmissionActions;
import dev.sccl.pierdolauth.admission.AdmissionService;
import dev.sccl.pierdolauth.admission.PaperAdmissionListener;
import java.time.Clock;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import dev.sccl.pierdolauth.config.ConfigLoader;
import dev.sccl.pierdolauth.licensed.FastLoginProjection;
import dev.sccl.pierdolauth.licensed.FastLoginBridge;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import java.util.concurrent.atomic.AtomicReference;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import dev.sccl.pierdolauth.whitelist.ServerPaperWhitelist;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PierdolAuthPlugin extends JavaPlugin {
    private PluginLifecycle lifecycle;
    private PaperAdmissionListener admissionListener;
    private HttpGameAuthClient creditClient;

    /** Enable-scoped publication callback, invoked only by successful open synchronizer completion. */
    public static java.util.function.Consumer<dev.sccl.pierdolauth.api.GameAuthModels.WhitelistResult.Snapshot>
            publication(dev.sccl.pierdolauth.whitelist.WhitelistRegistry registry,
                    java.util.function.Supplier<AdmissionService> admission) {
        return snapshot -> {
            registry.publish(snapshot.identities());
            var current = admission.get();
            if (current != null) current.revokeLicensed(registry.snapshot());
        };
    }

    interface BridgeOwner extends AutoCloseable {
        boolean ready();
        @Override void close();
    }
    private static final BridgeOwner UNREADY = new BridgeOwner() {
        public boolean ready() { return false; }
        public void close() {}
    };

    /** enable-scoped, main-thread owner - a linkage degradation cannot be reinstalled. */
    static final class FastLoginIntegration implements BridgeOwner {
        private volatile BridgeOwner bridge = UNREADY;
        private volatile boolean closed;

        void install(BridgeOwner next) {
            if (closed) next.close();
            else bridge = next;
        }

        public boolean ready() { return !closed && bridge.ready(); }

        public void close() {
            closed = true;
            var previous = bridge;
            bridge = UNREADY;
            previous.close();
        }
    }

    @Override
    public void onEnable() {
        // these references belong only to this enable attempt
        AtomicReference<AdmissionService> currentAdmission = new AtomicReference<>();
        var currentBridge = new FastLoginIntegration();
        lifecycle = new PluginLifecycle(() -> {
                    saveDefaultConfig();
                    return new ConfigLoader().load(getDataFolder().toPath().resolve("config.yml"));
                }, config -> {
                    creditClient = new HttpGameAuthClient(config.apiBaseUrl(), config.credentialFile());
                    return creditClient;
                },
                (client, config, registry) -> {
                    return new WhitelistSynchronizer(client,
                            new ServerPaperWhitelist(getServer()), config.whitelistSyncInterval(),
                            runnable -> getServer().getScheduler().runTask(this, runnable),
                             publication(registry, currentAdmission::get), fastLoginProjection(currentBridge));
                },
                (client, config, registry) -> {
                    var admission = new AdmissionService(client, registry,
                        config.sessionLeaseInterval(), Clock.systemUTC(),
                        (delay, task) -> {
                            long ticks = Math.max(1, (delay.toMillis() + 49) / 50);
                            var scheduled = getServer().getScheduler().runTaskLater(this, task, ticks);
                            return scheduled::cancel;
                        }, runnable -> getServer().getScheduler().runTask(this, runnable),
                        new AdmissionActions() {
                            @Override public void prompt(UUID uuid) {
                                message(uuid, "Please log in with /login <password>.");
                            }
                            @Override public void message(UUID uuid, String message) {
                                var player = getServer().getPlayer(uuid);
                                if (player != null) player.sendMessage(Component.text(message));
                            }
                            @Override public void release(UUID uuid) {
                                // authentication releases the event gates w no world state changes.
                            }
                            @Override public void kick(UUID uuid, String reason) {
                                var player = getServer().getPlayer(uuid);
                                if (player != null) {
                                    if (admissionListener != null) admissionListener.kick(player, reason);
                                    else player.kick(Component.text(reason));
                                }
                            }
                         }, 1024, currentBridge::ready);
                    currentAdmission.set(admission);
                    return admission;
                },
                (admission, registry) -> {
                    var listener = new PaperAdmissionListener(admission, registry,
                             currentBridge::ready,
                            runnable -> getServer().getScheduler().runTask(this, runnable));
                    admissionListener = listener;
                    return new PluginLifecycle.ListenerRegistration() {
                        @Override public void register() {
                            getServer().getPluginManager().registerEvents(listener, PierdolAuthPlugin.this);
                            currentBridge.install(openBridge(registry, listener));
                        }
                        @Override public void close() {
                            try { currentBridge.close(); }
                            finally {
                                currentAdmission.compareAndSet(admission, null);
                                try { listener.close(); }
                                finally { if (admissionListener == listener) admissionListener = null; }
                            }
                        }
                    };
                },
                new PluginLifecycle.ListenerRegistration() {
                    private final StartupFailureGuard guard = new StartupFailureGuard();
                    @Override public void register() {
                        getLogger().severe("PierdolAuth configuration or startup invalid; denying new connections.");
                        getServer().getPluginManager().registerEvents(guard, PierdolAuthPlugin.this);
                    }
                    @Override public void close() {
                        guard.close();
                    }
                });
        if (lifecycle.enable()) {
            Bukkit.getServicesManager().register(AuthenticatedPlayerService.class, new AuthenticatedPlayerService() {
                public java.util.OptionalLong authenticatedUserId(Player player) {
                    var listener = admissionListener;
                    return listener == null ? java.util.OptionalLong.empty() : listener.authenticatedUserId(player);
                }
                public java.util.concurrent.CompletionStage<Boolean> creditActiveMinutes(java.util.Collection<Long> userIds) {
                    var client = creditClient;
                    return client == null ? java.util.concurrent.CompletableFuture.completedFuture(false)
                            : client.creditActiveMinutes(userIds);
                }
                public int maxCreditBatchSize() { return 100; }
            }, this, org.bukkit.plugin.ServicePriority.Normal);
            getLogger().info("PierdolAuth enabled");
        }
    }

    private WhitelistSynchronizer.Projection fastLoginProjection(FastLoginIntegration integration) {
        try {
            var plugin = getServer().getPluginManager().getPlugin("FastLogin");
            if (!(plugin instanceof FastLoginBukkit fastLogin) || !fastLogin.isEnabled()
                    || fastLogin.getCore() == null || fastLogin.getCore().getStorage() == null) {
                return WhitelistSynchronizer.disabledProjection();
            }
            return new FastLoginProjection(fastLogin.getCore().getStorage(), Bukkit::isPrimaryThread,
                    integration::close);
        } catch (RuntimeException | LinkageError ignored) {
            return WhitelistSynchronizer.disabledProjection();
        }
    }

    private BridgeOwner openBridge(WhitelistRegistry registry, PaperAdmissionListener listener) {
        try {
            var plugin = getServer().getPluginManager().getPlugin("FastLogin");
            if (!(plugin instanceof FastLoginBukkit fastLogin) || !fastLogin.isEnabled()
                    || fastLogin.getCore() == null || fastLogin.getCore().getStorage() == null) return UNREADY;
            var bridge = new FastLoginBridge(new FastLoginBridge.Platform() {
                public boolean enabled() {
                    return fastLogin.isEnabled() && fastLogin.getCore() != null
                            && fastLogin.getCore().getStorage() != null;
                }
                public boolean initialized() { return fastLogin.isServerFullyStarted(); }
                public AuthPlugin<Player> hook() { return fastLogin.getCore().getAuthPluginHook(); }
                public void hook(AuthPlugin<Player> hook) { fastLogin.getCore().setAuthPluginHook(hook); }
                public void register(Listener bridge) {
                    getServer().getPluginManager().registerEvents(bridge, PierdolAuthPlugin.this);
                }
                public AdmissionService.Cancellation monitor(Runnable refresh) {
                    var task = getServer().getScheduler().runTaskTimer(PierdolAuthPlugin.this, refresh, 1L, 1L);
                    return task::cancel;
                }
            }, registry, listener);
            return new BridgeOwner() {
                public boolean ready() { return bridge.ready(); }
                public void close() { bridge.close(); }
            };
        } catch (RuntimeException | LinkageError ignored) {
            // optional integration failure must never disable cracked admission
            return UNREADY;
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        creditClient = null;
        if (lifecycle != null) lifecycle.close();
        lifecycle = null;
        admissionListener = null;
    }
}
