package dev.sccl.pierdolauth;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.config.ConfigurationException;
import dev.sccl.pierdolauth.config.PluginConfig;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;

/** Resource ownership separated from JavaPlugin's server-only construction. */
final class PluginLifecycle implements AutoCloseable {
    private final ConfigSource configSource;
    private final ClientFactory clients;
    private final SynchronizerFactory synchronizers;
    private final Runnable disable;
    private GameAuthClient client;
    private WhitelistSynchronizer synchronizer;

    @FunctionalInterface interface ConfigSource {
        PluginConfig load() throws ConfigurationException;
    }
    @FunctionalInterface interface ClientFactory {
        GameAuthClient create(PluginConfig config) throws ConfigurationException;
    }
    @FunctionalInterface interface SynchronizerFactory {
        WhitelistSynchronizer create(GameAuthClient client, PluginConfig config);
    }

    PluginLifecycle(ConfigSource configSource, ClientFactory clients,
            SynchronizerFactory synchronizers, Runnable disable) {
        this.configSource = configSource;
        this.clients = clients;
        this.synchronizers = synchronizers;
        this.disable = disable;
    }

    boolean enable() {
        try {
            PluginConfig config = configSource.load();
            client = clients.create(config);
            synchronizer = synchronizers.create(client, config);
            synchronizer.start();
            return true;
        } catch (ConfigurationException | RuntimeException ignored) {
            close();
            disable.run();
            return false;
        }
    }

    @Override public void close() {
        WhitelistSynchronizer previousSynchronizer = synchronizer;
        GameAuthClient previousClient = client;
        synchronizer = null;
        client = null;
        try {
            if (previousSynchronizer != null) previousSynchronizer.close();
        } catch (RuntimeException ignored) {
            // still close the credential-owning client
        } finally {
            try {
                if (previousClient != null) previousClient.close();
            } catch (RuntimeException ignored) {
                //sShutdown must not expose secret material or prevent plugin disable
            }
        }
    }
}
