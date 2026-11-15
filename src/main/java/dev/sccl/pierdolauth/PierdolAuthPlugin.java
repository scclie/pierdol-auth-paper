package dev.sccl.pierdolauth;

import dev.sccl.pierdolauth.config.ConfigLoader;
import dev.sccl.pierdolauth.config.ConfigurationException;
import dev.sccl.pierdolauth.config.PluginConfig;
import org.bukkit.plugin.java.JavaPlugin;

public final class PierdolAuthPlugin extends JavaPlugin {
    private PluginConfig config;

    @Override
    public void onEnable() {
        config = null;
        try {
            saveDefaultConfig();
            config = new ConfigLoader().load(getDataFolder().toPath().resolve("config.yml"));
        } catch (ConfigurationException | RuntimeException ignored) {
            config = null;
            getLogger().severe("PierdolAuth configuration invalid; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("PierdolAuth enabled");
    }

    @Override
    public void onDisable() {
        config = null;
    }
}
