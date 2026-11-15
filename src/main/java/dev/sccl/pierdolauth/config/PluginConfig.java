package dev.sccl.pierdolauth.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public record PluginConfig(String serverId, URI apiBaseUrl, Path credentialFile,
        Duration whitelistSyncInterval, Duration sessionLeaseInterval, String loginCommand) {}
