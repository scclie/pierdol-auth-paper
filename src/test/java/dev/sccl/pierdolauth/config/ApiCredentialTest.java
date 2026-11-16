package dev.sccl.pierdolauth.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiCredentialTest {
    @TempDir Path temporary;

    @ParameterizedTest(name = "owned credential newline normalization {index}")
    @ValueSource(strings = {"token", "token\n", "token\r\n", " token \n"})
    void readsValidatedOwnedBytesTrimmingOnlyOneFinalNewline(String content) throws Exception {
        Path file = temporary.resolve("credential");
        Files.writeString(file, content);
        byte[] owned = ApiCredential.readOwnedBytes(file);
        try {
            String expected = content.startsWith(" ") ? " token " : "token";
            assertTrue(java.util.Arrays.equals(expected.getBytes(StandardCharsets.US_ASCII), owned),
                    "Validated bytes must retain content and remove only a final LF/CRLF");
        } finally {
            java.util.Arrays.fill(owned, (byte) 0);
        }
    }

    @ParameterizedTest(name = "rejected owned credential {index}")
    @ValueSource(strings = {"", " \t\u000b\f", "\n", "token\nsecond", "token\n\n", "token\r", "token\r\n\r\n"})
    void rejectsBlankAndMultilineOwnedBytesWithGenericException(String content) throws Exception {
        Path file = temporary.resolve("credential");
        Files.writeString(file, content);
        ConfigurationException failure = assertThrows(ConfigurationException.class,
                () -> ApiCredential.readOwnedBytes(file));
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(content.isEmpty() ? "sentinel" : content));
    }

    @Test
    void missingCredentialReadFailsGenerically() {
        ConfigurationException failure = assertThrows(ConfigurationException.class,
                () -> ApiCredential.readOwnedBytes(temporary.resolve("missing")));
        assertNull(failure.getCause());
    }

    @Test
    void ownsValidatedBytesUntilCloseAndDoesNotReloadFile() throws Exception {
        Path file = temporary.resolve("credential");
        Files.writeString(file, "synthetic-token\r\n");
        try (ApiCredential credential = ApiCredential.open(file)) {
            // inspect the owned buffer only in tests; no production secret-byte accessor is exposed.
            var field = ApiCredential.class.getDeclaredField("bytes");
            field.setAccessible(true);
            byte[] owned = (byte[]) field.get(credential);
            assertTrue(java.util.Arrays.equals("synthetic-token".getBytes(StandardCharsets.US_ASCII), owned));
            Files.writeString(file, "replacement-token");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1/"));
            credential.authorize(request);
            assertTrue(request.build().headers().firstValue("Authorization")
                    .filter("Bearer synthetic-token"::equals).isPresent(), "Expected original synthetic bearer");
            credential.close();
            credential.close();
            assertArrayEquals(new byte[owned.length], owned);
            assertThrows(IllegalStateException.class, () -> credential.authorize(request));
        }
    }

    @Test
    void invalidHeaderFailureDoesNotExposeCredential() throws Exception {
        Path file = temporary.resolve("credential");
        Files.writeString(file, "\u0000credential-sentinel");
        try (ApiCredential credential = ApiCredential.open(file)) {
            RuntimeException failure = null;
            try {
                credential.authorize(HttpRequest.newBuilder(URI.create("http://127.0.0.1/")));
            } catch (RuntimeException caught) {
                failure = caught;
            }
            assertNotNull(failure, "Invalid bearer header must not be used");
            assertFalse(failure.toString().contains("credential-sentinel"), "Failure must redact credential");
            assertNull(failure.getCause(), "Failure must not retain a credential-bearing cause");
        }
    }

    @Test
    void wipesValidToken() {
        assertWiped("token", true);
    }

    @Test
    void wipesValidTokenWithCrlf() {
        assertWiped("token\r\n", true);
    }

    @Test
    void wipesRejectedEmptyContent() {
        assertWiped("", false);
    }

    @Test
    void wipesRejectedMultilineContent() {
        assertWiped("token\nsecond", false);
    }

    private void assertWiped(String synthetic, boolean valid) {
        byte[] bytes = synthetic.getBytes(StandardCharsets.US_ASCII);
        if (valid) {
            assertDoesNotThrow(() -> ApiCredential.validateAndWipe(bytes));
        } else {
            assertThrows(ConfigurationException.class, () -> ApiCredential.validateAndWipe(bytes));
        }
        assertArrayEquals(new byte[bytes.length], bytes);
    }
}
