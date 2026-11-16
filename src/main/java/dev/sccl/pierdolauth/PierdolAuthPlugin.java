package dev.sccl.pierdolauth;

import dev.sccl.pierdolauth.api.HttpGameAuthClient;
import dev.sccl.pierdolauth.config.ConfigLoader;
import dev.sccl.pierdolauth.whitelist.ServerPaperWhitelist;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PierdolAuthPlugin extends JavaPlugin {
    private PluginLifecycle lifecycle;

    @Override
    public void onEnable() {
        lifecycle = new PluginLifecycle(() -> {
                    saveDefaultConfig();
                    return new ConfigLoader().load(getDataFolder().toPath().resolve("config.yml"));
                }, config -> new HttpGameAuthClient(config.apiBaseUrl(), config.credentialFile()),
                (client, config) -> new WhitelistSynchronizer(client,
                        new ServerPaperWhitelist(getServer()), config.whitelistSyncInterval(),
                        runnable -> getServer().getScheduler().runTask(this, runnable)),
                () -> {
                    getLogger().severe("PierdolAuth configuration or startup invalid; disabling plugin.");
                    getServer().getPluginManager().disablePlugin(this);
                });
        if (lifecycle.enable()) getLogger().info("PierdolAuth enabled");
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) lifecycle.close();
        lifecycle = null;
    }
}
