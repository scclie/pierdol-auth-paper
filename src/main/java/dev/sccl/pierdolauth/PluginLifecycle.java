package dev.sccl.pierdolauth;

import dev.sccl.pierdolauth.api.GameAuthClient;
import dev.sccl.pierdolauth.admission.AdmissionService;
import dev.sccl.pierdolauth.config.ConfigurationException;
import dev.sccl.pierdolauth.config.PluginConfig;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;

/** Resource ownership separated from JavaPlugin's server-only construction. */
final class PluginLifecycle implements AutoCloseable {
    private final ConfigSource configSource;
    private final ClientFactory clients;
    private final SynchronizerFactory synchronizers;
    private final ListenerRegistration startupFailureGuard;
    private final AdmissionFactory admissions;
    private final ListenerFactory listeners;
    private AdmissionService admission;
    private ListenerRegistration listener;
    private GameAuthClient client;
    private WhitelistSynchronizer synchronizer;
    private WhitelistRegistry registry;

    @FunctionalInterface interface ConfigSource {
        PluginConfig load() throws ConfigurationException;
    }
    @FunctionalInterface interface ClientFactory {
        GameAuthClient create(PluginConfig config) throws ConfigurationException;
    }
    @FunctionalInterface interface SynchronizerFactory {
        WhitelistSynchronizer create(GameAuthClient client, PluginConfig config, WhitelistRegistry registry);
    }
    @FunctionalInterface interface AdmissionFactory {
        AdmissionService create(GameAuthClient client, PluginConfig config, WhitelistRegistry registry);
    }
    @FunctionalInterface interface ListenerFactory {
        ListenerRegistration create(AdmissionService admission, WhitelistRegistry registry);
    }
    interface ListenerRegistration extends AutoCloseable {
        void register();
        @Override void close();
    }

    PluginLifecycle(ConfigSource configSource, ClientFactory clients,
            SynchronizerFactory synchronizers, ListenerRegistration startupFailureGuard) {
        this(configSource, clients, synchronizers, (client, config, registry) -> null,
                (admission, registry) -> null, startupFailureGuard);
    }

    PluginLifecycle(ConfigSource configSource, ClientFactory clients,
            SynchronizerFactory synchronizers, AdmissionFactory admissions, ListenerFactory listeners,
            ListenerRegistration startupFailureGuard) {
        this.configSource = configSource;
        this.clients = clients;
        this.synchronizers = synchronizers;
        this.startupFailureGuard = startupFailureGuard;
        this.admissions = admissions;
        this.listeners = listeners;
    }

    boolean enable() {
        try {
            PluginConfig config = configSource.load();
            client = clients.create(config);
            registry = new WhitelistRegistry();
            synchronizer = synchronizers.create(client, config, registry);
            synchronizer.start();
            admission = admissions.create(client, config, registry());
            listener = listeners.create(admission, registry());
            if (listener != null) listener.register();
            return true;
        } catch (ConfigurationException | RuntimeException ignored) {
            close();
            listener = startupFailureGuard;
            listener.register();
            return false;
        }
    }

    /** enable-scoped metadata for later admission injection absent before enable and after close/failure. */
    WhitelistRegistry registry() {
        return registry;
    }

    @Override public void close() {
        ListenerRegistration previousListener = listener;
        AdmissionService previousAdmission = admission;
        WhitelistSynchronizer previousSynchronizer = synchronizer;
        GameAuthClient previousClient = client;
        synchronizer = null;
        client = null;
        registry = null;
        listener = null;
        admission = null;
        try {
            if (previousListener != null) previousListener.close();
        } catch (RuntimeException ignored) {
            // continue shutting down all owned resources without exposing request data.
        }
        try {
            if (previousAdmission != null) previousAdmission.close();
        } catch (RuntimeException ignored) {
            // the shared client must still close.
        }
        try {
            if (previousSynchronizer != null) previousSynchronizer.close();
        } catch (RuntimeException ignored) {
            // still close the credential-owning client
        } finally {
            try {
                if (previousClient != null) previousClient.close();
            } catch (RuntimeException ignored) {
                // shutdown must not expose secret material or prevent plugin disable.
            }
        }
    }
}
