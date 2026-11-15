package dev.sccl.pierdolauth.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class ApiCredential {
    private ApiCredential() {}

    static void validate(Path file) throws ConfigurationException {
        try {
            validateAndWipe(Files.readAllBytes(file));
        } catch (IOException | RuntimeException ignored) {
            throw new ConfigurationException();
        }
    }

    static void validateAndWipe(byte[] bytes) throws ConfigurationException {
        try {
            int end = bytes.length;
            if (end > 0 && bytes[end - 1] == '\n') {
                end--;
                if (end > 0 && bytes[end - 1] == '\r') {
                    end--;
                }
            }
            boolean hasContent = false;
            for (int index = 0; index < end; index++) {
                byte value = bytes[index];
                if (value == '\r' || value == '\n') {
                    throw new ConfigurationException();
                }
                if (value != ' ' && value != '\t' && value != 0x0b && value != '\f') {
                    hasContent = true;
                }
            }
            if (!hasContent) {
                throw new ConfigurationException();
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
