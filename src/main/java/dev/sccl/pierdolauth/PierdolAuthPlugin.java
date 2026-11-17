package dev.sccl.pierdolauth;

import dev.sccl.pierdolauth.api.HttpGameAuthClient;
import dev.sccl.pierdolauth.admission.AdmissionActions;
import dev.sccl.pierdolauth.admission.AdmissionService;
import dev.sccl.pierdolauth.admission.PaperAdmissionListener;
import java.time.Clock;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import dev.sccl.pierdolauth.config.ConfigLoader;
import dev.sccl.pierdolauth.whitelist.ServerPaperWhitelist;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PierdolAuthPlugin extends JavaPlugin {
    private PluginLifecycle lifecycle;
    private PaperAdmissionListener admissionListener;

    @Override
    public void onEnable() {
        lifecycle = new PluginLifecycle(() -> {
                    saveDefaultConfig();
                    return new ConfigLoader().load(getDataFolder().toPath().resolve("config.yml"));
                }, config -> new HttpGameAuthClient(config.apiBaseUrl(), config.credentialFile()),
                (client, config, registry) -> {
                    return new WhitelistSynchronizer(client,
                            new ServerPaperWhitelist(getServer()), config.whitelistSyncInterval(),
                            runnable -> getServer().getScheduler().runTask(this, runnable),
                            snapshot -> registry.publish(snapshot.identities()));
                },
                (client, config, registry) -> new AdmissionService(client, registry,
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
                        }, 1024),
                (admission, registry) -> {
                    var listener = new PaperAdmissionListener(admission, registry);
                    admissionListener = listener;
                    return new PluginLifecycle.ListenerRegistration() {
                        @Override public void register() {
                            getServer().getPluginManager().registerEvents(listener, PierdolAuthPlugin.this);
                        }
                        @Override public void close() {
                            try { listener.close(); }
                            finally { if (admissionListener == listener) admissionListener = null; }
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
        if (lifecycle.enable()) getLogger().info("PierdolAuth enabled");
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) lifecycle.close();
        lifecycle = null;
        admissionListener = null;
    }
}
