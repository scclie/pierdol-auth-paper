package dev.sccl.pierdolauth.config;

public final class ConfigurationException extends Exception {
    public ConfigurationException() {
        super("Invalid plugin configuration.", null, false, false);
    }
}
