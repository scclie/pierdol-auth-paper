package dev.sccl.pierdolauth.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginConfigTest {
    @TempDir
    Path directory;

    private Path credential;
    private Path config;
    private String yaml;

    private void fixture() throws IOException {
        credential = directory.resolve("credential");
        config = directory.resolve("config.yml");
        Files.write(credential, "unit-token".getBytes(StandardCharsets.US_ASCII));
        yaml = """
                server-id: sb
                api-base-url: http://mine-platform:8088/internal/game-auth/v1
                credential-file: '%s'
                whitelist-sync-seconds: 60
                session-lease-seconds: 120
                login-command: login
                """.formatted(credential);
        Files.writeString(config, yaml);
    }

    private void replace(String original, String replacement) throws IOException {
        Files.writeString(config, yaml.replace(original, replacement));
    }

    @Test
    void loadsAllPublicValues() throws Exception {
        fixture();
        PluginConfig result = new ConfigLoader().load(config);
        assertAll(
                () -> assertEquals("sb", result.serverId()),
                () -> assertEquals(URI.create("http://mine-platform:8088/internal/game-auth/v1"), result.apiBaseUrl()),
                () -> assertEquals(credential, result.credentialFile()),
                () -> assertEquals(Duration.ofSeconds(60), result.whitelistSyncInterval()),
                () -> assertEquals(Duration.ofSeconds(120), result.sessionLeaseInterval()),
                () -> assertEquals("login", result.loginCommand()));
    }

    @Test
    void rejectsMissingAndBlankServerId() throws Exception {
        fixture();
        for (String value : new String[] {"", "server-id: \"\"", "server-id: \" \\t\""}) {
            replace("server-id: sb", value);
            assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
        }
    }

    @Test
    void rejectsInvalidAndNonHttpUris() throws Exception {
        fixture();
        for (String value : new String[] {"not a uri", "/relative", "http:///path", "https://mine-platform", "file:///tmp/x"}) {
            replace("http://mine-platform:8088/internal/game-auth/v1", value);
            assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
        }
    }

    @Test
    void rejectsMissingEmptyWhitespaceAndMultilineCredentials() throws Exception {
        fixture();
        Files.delete(credential);
        assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
        for (String value : new String[] {"", " \t", "token\nsecond", "token\rsecond", "token\n\n", "token\r", "\n", "\r\n"}) {
            Files.write(credential, value.getBytes(StandardCharsets.US_ASCII));
            assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
        }
    }

    @Test
    void rejectsEachNonPositiveInterval() throws Exception {
        fixture();
        for (String key : new String[] {"whitelist-sync-seconds: 60", "session-lease-seconds: 120"}) {
            for (int value : new int[] {0, -1}) {
                replace(key, key.substring(0, key.indexOf(':') + 1) + " " + value);
                assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
            }
        }
    }

    @Test
    void rejectsWrongLoginCommands() throws Exception {
        fixture();
        for (String value : new String[] {"register", "Login", "/login"}) {
            replace("login-command: login", "login-command: " + value);
            assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
        }
    }

    @Test
    void acceptsOneTerminalLfOrCrlfWithoutChangingFile() throws Exception {
        fixture();
        for (String value : new String[] {"unit-token\n", "unit-token\r\n"}) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            Files.write(credential, bytes);
            assertDoesNotThrow(() -> new ConfigLoader().load(config));
            assertArrayEquals(bytes, Files.readAllBytes(credential));
        }
    }

    @Test
    void failureDoesNotExposeCredentialOrCause() throws Exception {
        fixture();
        String marker = "synthetic-secret-marker";
        Files.write(credential, (marker + "\nsecond").getBytes(StandardCharsets.US_ASCII));
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(config));
        assertEquals("Invalid plugin configuration.", failure.getMessage());
        assertFalse(failure.getMessage().contains(marker));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}
