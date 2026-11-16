package dev.sccl.pierdolauth.config;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Owns credential bytes; only an HTTP header, never the bytes, crosses this boundary. */
public final class ApiCredential implements AutoCloseable {
    private final byte[] bytes;
    private boolean closed;

    private ApiCredential(byte[] ownedBytes) {
        bytes = ownedBytes;
    }

    public static ApiCredential open(Path file) throws ConfigurationException {
        return new ApiCredential(readOwnedBytes(file));
    }

    public synchronized void authorize(HttpRequest.Builder request) {
        if (closed) throw new IllegalStateException("Credential is closed");
        try {
            request.setHeader("Authorization", "Bearer " + new String(bytes, StandardCharsets.US_ASCII));
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("Credential cannot be used");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        Arrays.fill(bytes, (byte) 0);
    }

    static byte[] readOwnedBytes(Path file) throws ConfigurationException {
        byte[] raw = null;
        try {
            raw = Files.readAllBytes(file);
            return Arrays.copyOf(raw, validatedEnd(raw));
        } catch (IOException | RuntimeException ignored) {
            throw new ConfigurationException();
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    static void validate(Path file) throws ConfigurationException {
        try {
            byte[] owned = readOwnedBytes(file);
            Arrays.fill(owned, (byte) 0);
        } catch (RuntimeException ignored) {
            throw new ConfigurationException();
        }
    }

    static void validateAndWipe(byte[] bytes) throws ConfigurationException {
        try {
            validatedEnd(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static int validatedEnd(byte[] bytes) throws ConfigurationException {
        int end = bytes.length;
        if (end > 0 && bytes[end - 1] == '\n') {
            end--;
            if (end > 0 && bytes[end - 1] == '\r') end--;
        }
        boolean hasContent = false;
        for (int index = 0; index < end; index++) {
            byte value = bytes[index];
            if (value == '\r' || value == '\n') throw new ConfigurationException();
            if (value != ' ' && value != '\t' && value != 0x0b && value != '\f') hasContent = true;
        }
        if (!hasContent) throw new ConfigurationException();
        return end;
    }
}
