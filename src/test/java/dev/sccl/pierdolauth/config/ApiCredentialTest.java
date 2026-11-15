package dev.sccl.pierdolauth.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ApiCredentialTest {
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
