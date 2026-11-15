package dev.sccl.pierdolauth.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigLoader {
    public PluginConfig load(Path configFile) throws ConfigurationException {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(configFile.toFile());

            String serverId = requiredString(yaml.get("server-id"));
            URI apiBaseUrl = new URI(requiredString(yaml.get("api-base-url")));
            if (!apiBaseUrl.isAbsolute() || apiBaseUrl.isOpaque()
                    || !"http".equalsIgnoreCase(apiBaseUrl.getScheme())
                    || apiBaseUrl.getHost() == null || apiBaseUrl.getRawUserInfo() != null
                    || apiBaseUrl.getRawQuery() != null || apiBaseUrl.getRawFragment() != null
                    || (apiBaseUrl.getPort() != -1
                            && (apiBaseUrl.getPort() < 1 || apiBaseUrl.getPort() > 65535))) {
                throw new ConfigurationException();
            }
            Path credentialFile = Path.of(requiredString(yaml.get("credential-file")));
            Duration whitelistSyncInterval = interval(yaml.get("whitelist-sync-seconds"));
            Duration sessionLeaseInterval = interval(yaml.get("session-lease-seconds"));
            String loginCommand = requiredString(yaml.get("login-command"));
            if (!"login".equals(loginCommand) || !credentialFile.isAbsolute()
                    || !Files.isRegularFile(credentialFile)) {
                throw new ConfigurationException();
            }

            ApiCredential.validate(credentialFile);
            return new PluginConfig(serverId, apiBaseUrl, credentialFile,
                    whitelistSyncInterval, sessionLeaseInterval, loginCommand);
        } catch (IOException | InvalidConfigurationException | URISyntaxException | RuntimeException ignored) {
            throw new ConfigurationException();
        }
    }

    private static String requiredString(Object value) throws ConfigurationException {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ConfigurationException();
        }
        return text;
    }

    private static Duration interval(Object value) throws ConfigurationException {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new ConfigurationException();
        }
        long seconds = ((Number) value).longValue();
        if (seconds <= 0) {
            throw new ConfigurationException();
        }
        return Duration.ofSeconds(seconds);
    }
}
