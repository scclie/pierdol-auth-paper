package dev.sccl.pierdolauth.api;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sccl.pierdolauth.whitelist.PaperWhitelist;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpGameAuthClientTest {
    private static final UUID AUTH_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private final String syntheticLease = java.util.stream.IntStream.range(0, 64)
            .mapToObj(index -> Integer.toHexString(index % 16)).collect(java.util.stream.Collectors.joining());
    private char[] expectedPassword;
    private volatile boolean exactPostBody;

    @Test
    void crackedLoginSendsExactPostAndRetainsOnlySessionIdentity() throws Exception {
        body = authEnvelope("allow", 1700000000, 7);
        expectedPassword = (UUID.randomUUID().toString() + (char) 34 + (char) 92 + (char) 10 + (char) 233).toCharArray();
        char[] owned = expectedPassword.clone();
        var stage = client.crackedLogin("Player", owned);
        assertTrue(allCleared(owned), "Owned input must be cleared synchronously");
        var result = assertInstanceOf(CrackedLoginResult.Allowed.class, await(stage));
        assertEquals(AUTH_UUID, result.uuid());
        assertTrue(syntheticLease.equals(result.lease()), "Session identity must be retained");
        assertEquals(1700000000, result.expiresAtEpochSeconds());
        assertEquals(3, result.getClass().getRecordComponents().length);
        assertPost("cracked-login");
    }

    @Test
    void sessionCheckSendsExactPostAndRetainsOnlySessionIdentity() throws Exception {
        body = authEnvelope("continue", 1700000001, 8);
        var result = assertInstanceOf(SessionCheckResult.Continued.class, await(client.checkSession(syntheticLease)));
        assertEquals(AUTH_UUID, result.uuid());
        assertTrue(syntheticLease.equals(result.lease()), "Session identity must be retained");
        assertEquals(1700000001, result.expiresAtEpochSeconds());
        assertEquals(3, result.getClass().getRecordComponents().length);
        assertPost("session-check");
    }

    private String authEnvelope(String outcome, long expiry, long version) {
        return "{\"outcome\":\"" + outcome + "\",\"uuid\":\"" + AUTH_UUID
                + "\",\"lease\":\"" + syntheticLease + "\",\"expires_at\":" + expiry
                + ",\"whitelist_version\":" + version + "}";
    }

    private static boolean allCleared(char[] value) {
        for (char character : value) if (character != 0) return false;
        return true;
    }

    @ParameterizedTest(name = "explicit negative envelope {index}")
    @ValueSource(booleans = {false, true})
    void mapsOnlyExactNegativeEnvelope(boolean session) throws Exception {
        body = "{\"outcome\":\"" + (session ? "disconnect" : "deny") + "\",\"whitelist_version\":7}";
        Object result = await(authCall(session));
        assertTrue(session ? result instanceof SessionCheckResult.Disconnected
                : result instanceof CrackedLoginResult.Denied, "Expected explicit negative result");
    }

    @ParameterizedTest(name = "invalid authentication envelopes {index}")
    @ValueSource(booleans = {false, true})
    void rejectsEveryMalformedAuthenticationEnvelope(boolean session) throws Exception {
        String valid = authEnvelope(session ? "continue" : "allow", 1700000000, 7);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> invalid = new ArrayList<>(List.of("", "{", "null", "[]", "42", "\"text\"", "{}",
                valid + " {}", valid.replaceFirst("\\{", "{\"outcome\":\"deny\","),
                valid.substring(0, valid.length() - 1) + ",\"extra\":true}",
                "{\"outcome\":\"" + (session ? "deny" : "disconnect") + "\",\"whitelist_version\":7}"));
        for (String field : List.of("outcome", "uuid", "lease", "expires_at", "whitelist_version")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
            node.remove(field);
            invalid.add(node.toString());
            for (String replacement : List.of("null", "true", "[]", "{}", "42.5")) {
                node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
                node.set(field, mapper.readTree(replacement));
                invalid.add(node.toString());
            }
        }
        for (String field : List.of("expires_at", "whitelist_version")) {
            for (String replacement : List.of("-1", "1.0", "9223372036854775808", "\"7\"")) {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
                node.set(field, mapper.readTree(replacement));
                invalid.add(node.toString());
            }
        }
        for (String uuid : List.of("invalid", "1-1-1-1-1", AUTH_UUID + " ")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
            node.put("uuid", uuid);
            invalid.add(node.toString());
        }
        for (String lease : List.of("", " ", "a".repeat(63), "a".repeat(65), "g".repeat(64))) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
            node.put("lease", lease);
            invalid.add(node.toString());
        }
        for (String outcome : List.of("unknown", session ? "allow" : "continue", session ? "deny" : "disconnect")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
            node.put("outcome", outcome);
            invalid.add(node.toString());
        }
        String negative = "{\"outcome\":\"" + (session ? "disconnect" : "deny") + "\",\"whitelist_version\":7}";
        invalid.add(negative.replace("7", "-1"));
        invalid.add(negative.replace("7", "7.0"));
        invalid.add(negative.replace("7", "null"));
        invalid.add(negative.replace("}", ",\"extra\":true}"));
        for (String response : invalid) {
            body = response;
            assertAuthUnavailable(await(authCall(session)), session);
        }
    }

    @ParameterizedTest(name = "authentication numeric boundaries {index}")
    @ValueSource(booleans = {false, true})
    void acceptsUppercaseHexAndNonnegativeLongBoundaries(boolean session) throws Exception {
        for (long number : new long[] {0, Long.MAX_VALUE}) {
            body = authEnvelope(session ? "continue" : "allow", number, number)
                    .replace(syntheticLease, syntheticLease.toUpperCase(java.util.Locale.ROOT));
            Object result = await(authCall(session));
            assertTrue(session ? result instanceof SessionCheckResult.Continued
                    : result instanceof CrackedLoginResult.Allowed, "Expected valid boundary result");
        }
    }

    @ParameterizedTest(name = "authentication status rejection {index}")
    @ValueSource(booleans = {false, true})
    void rejectsNon200PostsWithoutFollowingRedirects(boolean session) throws Exception {
        body = authEnvelope(session ? "continue" : "allow", 1700000000, 7);
        for (int code : new int[] {201, 204, 301, 302, 307, 400, 401, 403, 404, 429, 500, 503}) {
            requests.clear();
            status = code;
            assertAuthUnavailable(await(authCall(session)), session);
            assertEquals(1, requests.size());
        }
    }

    @ParameterizedTest(name = "bounded authentication body {index}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void boundsPostsAndRecoversForLaterGetAndPost(boolean session, boolean useChunks) throws Exception {
        chunked = useChunks;
        String valid = authEnvelope(session ? "continue" : "allow", 1700000000, 7);
        body = padded(valid, 1_048_576);
        Object boundary = await(authCall(session));
        assertTrue(session ? boundary instanceof SessionCheckResult.Continued
                : boundary instanceof CrackedLoginResult.Allowed, "Body at cap must be accepted");
        body = padded(valid, 1_048_577);
        assertAuthUnavailable(await(authCall(session)), session);
        body = "{\"outcome\":\"licensed\"}";
        assertEquals(PolicyResult.LICENSED, await(client.fetchPolicy("Player")));
        body = valid;
        Object recovered = await(authCall(session));
        assertTrue(session ? recovered instanceof SessionCheckResult.Continued
                : recovered instanceof CrackedLoginResult.Allowed, "POST must recover after overflow");
        status = 503;
        assertAuthUnavailable(await(authCall(session)), session);
        status = 200;
        assertTrue(session ? await(authCall(true)) instanceof SessionCheckResult.Continued
                : await(authCall(false)) instanceof CrackedLoginResult.Allowed, "POST must recover after failure");
    }

    @Test
    void invalidInputsNeverSendAndAlwaysClearOwnedPassword() throws Exception {
        for (String nickname : new String[] {null, "", "  ", "Player\nOne", "\u0000", "\ud800"}) {
            char[] owned = UUID.randomUUID().toString().toCharArray();
            assertAuthUnavailable(await(client.crackedLogin(nickname, owned)), false);
            assertTrue(allCleared(owned), "Invalid input must still be cleared");
        }
        assertAuthUnavailable(await(client.crackedLogin("Player", null)), false);
        for (String lease : new String[] {null, "", " ", "a".repeat(63), "a".repeat(65), "g".repeat(64)}) {
            assertAuthUnavailable(await(client.checkSession(lease)), true);
        }
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest(name = "authentication timeout {index}")
    @ValueSource(booleans = {false, true})
    void postTimeoutIsUnavailableWithoutBlockingCaller(boolean session) throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        var stage = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> authCall(session));
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        assertFalse(stage.toCompletableFuture().isDone());
        assertAuthUnavailable(await(stage), session);
    }

    @Test
    void closeAndTransportFailureReturnUnavailableWithoutDiagnosticDisclosure() throws Exception {
        var messages = new StringBuilder();
        var logger = java.util.logging.Logger.getLogger("");
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                messages.append(record.getMessage());
                if (record.getThrown() != null) messages.append(record.getThrown().getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        expectedPassword = UUID.randomUUID().toString().toCharArray();
        try {
            body = new String(expectedPassword);
            assertAuthUnavailable(await(client.crackedLogin("Player", expectedPassword.clone())), false);
            server.stop(0);
            assertAuthUnavailable(await(authCall(false)), false);
            assertAuthUnavailable(await(authCall(true)), true);
            client.close();
            char[] owned = expectedPassword.clone();
            assertAuthUnavailable(await(client.crackedLogin("Player", owned)), false);
            assertTrue(allCleared(owned), "Closed client must clear owned input");
            assertAuthUnavailable(await(authCall(true)), true);
            assertFalse(messages.toString().contains(new String(expectedPassword)), "Diagnostics must be value-free");
            assertFalse(messages.toString().contains(syntheticLease), "Diagnostics must be value-free");
            String authorization = Files.readString(temporary.resolve("credential")).strip();
            assertFalse(messages.toString().contains(authorization), "Diagnostics must be value-free");
        } finally {
            logger.removeHandler(handler);
        }
    }

    private CompletionStage<?> authCall(boolean session) {
        return session ? client.checkSession(syntheticLease)
                : client.crackedLogin("Player", UUID.randomUUID().toString().toCharArray());
    }

    private static void assertAuthUnavailable(Object result, boolean session) {
        assertTrue(session ? result instanceof SessionCheckResult.Unavailable
                : result instanceof CrackedLoginResult.Unavailable, "Expected unavailable result");
    }

    @Test
    void authenticationResultDiagnosticsDoNotDiscloseSessionMaterial() {
        var allowed = new CrackedLoginResult.Allowed(AUTH_UUID, syntheticLease, 1700000000);
        var continued = new SessionCheckResult.Continued(AUTH_UUID, syntheticLease, 1700000001);
        assertFalse(allowed.toString().contains(syntheticLease), "Result diagnostics must be value-free");
        assertFalse(continued.toString().contains(syntheticLease), "Result diagnostics must be value-free");
    }

    @ParameterizedTest(name = "oversized unfinished authentication stream {index}")
    @CsvSource({"false,200", "true,200", "false,503", "true,503"})
    void rejectsOversizedPostBeforeStreamEnds(boolean session, int responseStatus) throws Exception {
        status = responseStatus;
        chunked = true;
        body = padded(authEnvelope(session ? "continue" : "allow", 1700000000, 7), 1_048_577);
        bodySent = new CountDownLatch(1);
        bodyEndGate = new CountDownLatch(1);
        var stage = authCall(session);
        assertTrue(bodySent.await(2, TimeUnit.SECONDS));
        Object result = assertTimeoutPreemptively(java.time.Duration.ofMillis(1500), () -> await(stage));
        assertAuthUnavailable(result, session);
        assertEquals(1, bodyEndGate.getCount(), "Must reject before end of stream");
    }

    @Test
    void closingClientCompletesInflightAndQueuedPostsAndClearsPasswords() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        var inflight = authCall(true);
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        List<CompletionStage<?>> queued = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            char[] owned = UUID.randomUUID().toString().toCharArray();
            queued.add(client.crackedLogin("Player", owned));
            assertTrue(allCleared(owned), "Queued input must be cleared synchronously");
        }
        client.close();
        assertAuthUnavailable(await(inflight), true);
        for (var stage : queued) assertAuthUnavailable(await(stage), false);
    }

    @Test
    void saturatedExecutorRejectsPostsWithoutBlockingOrRetainingOwnedPassword() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        authCall(true);
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        List<CompletionStage<?>> results = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
            List<CompletionStage<?>> submitted = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                char[] owned = UUID.randomUUID().toString().toCharArray();
                submitted.add(client.crackedLogin("Player", owned));
                assertTrue(allCleared(owned), "Saturated input must be cleared synchronously");
            }
            return submitted;
        });
        assertTrue(results.stream().anyMatch(stage -> stage.toCompletableFuture().isDone()),
                "Bounded queue must reject excess POST work");
        for (var stage : results) {
            if (stage.toCompletableFuture().isDone()) assertAuthUnavailable(await(stage), false);
        }
    }

    @Test
    void retainedRequestBytesAreClearedBeforeCompletionConsumersRun() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        client.fetchPolicy("Player");
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        client.fetchPolicy("Player");
        var stage = authCall(false);
        var executorField = HttpGameAuthClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        var executor = (java.util.concurrent.ThreadPoolExecutor) executorField.get(client);
        Runnable queued = executor.getQueue().peek();
        assertNotNull(queued, "POST must be queued behind blocked transport work");
        java.net.http.HttpRequest captured = null;
        for (var field : queued.getClass().getDeclaredFields()) {
            if (field.getType() == java.net.http.HttpRequest.class) {
                field.setAccessible(true);
                captured = (java.net.http.HttpRequest) field.get(queued);
            }
        }
        assertNotNull(captured, "Queued work must own its synchronously built request");
        var publisher = captured.bodyPublisher().orElseThrow();
        var observed = stage.thenApply(ignored -> publisherContainsOnlyClearedBytes(publisher));
        responseGate.countDown();
        assertTrue(await(observed), "Retained mutable bytes must be cleared before completion consumers");
    }

    @ParameterizedTest(name = "external terminal operation preserves queued POST {index}")
    @ValueSource(strings = {"cancel", "complete", "exception", "obtrude", "obtrudeException"})
    void externalTerminalOperationsCannotAlterQueuedPostOrExposeUnclearedBytes(String operation) throws Exception {
        externalTerminalOperationPreservesTransport(operation, false);
    }

    @ParameterizedTest(name = "external terminal operation preserves in-flight POST {index}")
    @ValueSource(strings = {"cancel", "complete", "exception", "obtrude", "obtrudeException"})
    void externalTerminalOperationsCannotFinishInflightPostBeforeCleanup(String operation) throws Exception {
        externalTerminalOperationPreservesTransport(operation, true);
    }

    private void externalTerminalOperationPreservesTransport(String operation, boolean inflight) throws Exception {
        var executorField = HttpGameAuthClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        var executor = (java.util.concurrent.ThreadPoolExecutor) executorField.get(client);
        var workersBlocked = new CountDownLatch(2);
        var workerGate = new CountDownLatch(1);
        Runnable block = () -> {
            workersBlocked.countDown();
            try {
                workerGate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        executor.execute(block);
        executor.execute(block);
        try {
            assertTrue(workersBlocked.await(2, TimeUnit.SECONDS), "Both transport workers must be gated");
            expectedPassword = UUID.randomUUID().toString().toCharArray();
            body = "{\"outcome\":\"deny\",\"whitelist_version\":7}";
            responseGate = new CountDownLatch(1);
            requestReceived = new CountDownLatch(1);
            var stage = client.crackedLogin("Player", expectedPassword.clone());
            Runnable queued = executor.getQueue().peek();
            assertNotNull(queued, "POST must remain queued until transport is released");
            java.net.http.HttpRequest captured = null;
            for (var field : queued.getClass().getDeclaredFields()) {
                if (field.getType() == java.net.http.HttpRequest.class) {
                    field.setAccessible(true);
                    captured = (java.net.http.HttpRequest) field.get(queued);
                }
            }
            assertNotNull(captured, "Queued work must own its synchronously built request");
            var publisher = captured.bodyPublisher().orElseThrow();
            var observed = stage.handle((value, failure) -> publisherContainsOnlyClearedBytes(publisher));
            if (inflight) {
                workerGate.countDown();
                assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "POST must be in flight");
            }
            // Exercise both a direct downcast (if exposed) and the public conversion.
            if (stage instanceof CompletableFuture<CrackedLoginResult> direct) {
                attemptExternalTerminalOperation(direct, operation);
            }
            attemptExternalTerminalOperation(stage.toCompletableFuture(), operation);
            boolean observerRanEarly = observed.toCompletableFuture().isDone();
            boolean bytesClearedEarly = publisherContainsOnlyClearedBytes(publisher);
            workerGate.countDown();
            responseGate.countDown();
            // observe actual transport, not just the caller-controlled future's result
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "Queued POST must retain a valid wire body");
            assertAll(
                    () -> assertFalse(observerRanEarly, "External termination must not finish the public stage"),
                    () -> assertFalse(bytesClearedEarly, "Active transport must retain its original request bytes"),
                    () -> assertTrue(await(observed), "Observers must run only after internal cleanup"),
                    () -> assertTrue(await(stage) instanceof CrackedLoginResult.Denied,
                            "Only the internally parsed result may finish the public stage"),
                    () -> assertTrue(exactPostBody, "External termination must not alter wire bytes"));
            assertPost("cracked-login");
        } finally {
            workerGate.countDown();
        }
    }

    private static void attemptExternalTerminalOperation(CompletableFuture<CrackedLoginResult> future,
            String operation) {
        try {
            switch (operation) {
                case "cancel" -> future.cancel(true);
                case "complete" -> future.complete(new CrackedLoginResult.Unavailable());
                case "exception" -> future.completeExceptionally(new IllegalStateException());
                case "obtrude" -> future.obtrudeValue(new CrackedLoginResult.Unavailable());
                case "obtrudeException" -> future.obtrudeException(new IllegalStateException());
                default -> throw new AssertionError("Unknown terminal operation");
            }
        } catch (UnsupportedOperationException expected) {
            // read-only stage
        }
    }

    private static boolean publisherContainsOnlyClearedBytes(java.net.http.HttpRequest.BodyPublisher publisher) {
        var observed = new CompletableFuture<Boolean>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            private boolean cleared = true;
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(java.nio.ByteBuffer buffer) {
                while (buffer.hasRemaining()) if (buffer.get() != 0) cleared = false;
            }
            @Override public void onError(Throwable failure) { observed.complete(false); }
            @Override public void onComplete() { observed.complete(cleared); }
        });
        return observed.join();
    }

    private void assertPost(String endpoint) throws Exception {
        Request request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/internal/game-auth/v1/" + endpoint, request.target());
        assertTrue(request.authorized(), "Expected synthetic authorization");
        assertEquals("application/json", request.accept());
        assertEquals("no-store", request.cacheControl());
        assertTrue(exactPostBody, "Expected exact request fields and encoding");
    }
    @TempDir Path temporary;
    private HttpServer server;
    private HttpGameAuthClient client;
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private volatile String body = "{\"outcome\":\"licensed\"}";
    private volatile int status = 200;
    private volatile CountDownLatch responseGate;
    private volatile CountDownLatch requestReceived;
    private volatile boolean chunked;
    private volatile CountDownLatch bodySent;
    private volatile CountDownLatch bodyEndGate;

    @BeforeEach
    void startLoopback() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        Path credential = temporary.resolve("credential");
        Files.writeString(credential, "credential-sentinel\r\n", StandardCharsets.US_ASCII);
        client = new HttpGameAuthClient(baseUrl(), credential);
    }

    @AfterEach
    void stopLoopback() {
        if (responseGate != null) responseGate.countDown();
        if (bodyEndGate != null) bodyEndGate.countDown();
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test
    void sendsExactPolicyRequestWithoutExposingCredentialInAssertions() throws Exception {
        assertEquals(PolicyResult.LICENSED, await(client.fetchPolicy("Player One")));
        Request request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/internal/game-auth/v1/identity-policy?nickname=Player%20One", request.target());
        assertTrue(request.authorized(), "Expected synthetic bearer authorization");
        assertEquals("application/json", request.accept());
        assertEquals("no-store", request.cacheControl());
    }

    @ParameterizedTest(name = "policy outcome {index}")
    @CsvSource({"denied,DENIED", "cracked,CRACKED", "licensed,LICENSED"})
    void mapsLiteralPolicyOutcomes(String outcome, PolicyResult expected) throws Exception {
        body = "{\"outcome\":\"" + outcome + "\"}";
        assertEquals(expected, await(client.fetchPolicy("Player")));
    }

    @ParameterizedTest(name = "invalid policy body {index}")
    @ValueSource(strings = {
        "{}", "[]", "null", "42", "\"licensed\"", "{", "",
        "{\"outcome\":null}", "{\"outcome\":1}", "{\"outcome\":true}",
        "{\"outcome\":\"unknown\"}", "{\"outcome\":\"LICENSED\"}",
        "{\"outcome\":\"licensed\",\"extra\":true}",
        "{\"outcome\":\"denied\",\"outcome\":\"licensed\"}",
        "{\"outcome\":\"licensed\"} {}",
        "{\"outcome\":\"credential-sentinel\"}"
    })
    void rejectsMalformedOrNonExactPolicyBodies(String response) throws Exception {
        body = response;
        assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy("Player")));
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 301, 302, 307, 400, 401, 403, 404, 429, 500, 503})
    void rejectsEveryStatusOtherThan200WithoutFollowingRedirects(int responseStatus) throws Exception {
        status = responseStatus;
        assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy("Player")));
        assertEquals(1, requests.size());
    }

    @Test
    void encodesReservedAndUnicodeQueryCharacters() throws Exception {
        assertEquals(PolicyResult.LICENSED, await(client.fetchPolicy("A+B&é?#")));
        assertEquals("/internal/game-auth/v1/identity-policy?nickname=A%2BB%26%C3%A9%3F%23",
                requests.remove().target());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsCompletePolicyAtOneMiB(boolean useChunks) throws Exception {
        chunked = useChunks;
        body = padded("{\"outcome\":\"licensed\"}", 1_048_576);
        assertEquals(PolicyResult.LICENSED, await(client.fetchPolicy("Player")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsCompleteWhitelistAtOneMiB(boolean useChunks) throws Exception {
        chunked = useChunks;
        body = padded("{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":[]}", 1_048_576);
        assertEquals(new WhitelistResult.Snapshot(42, List.of()),
                await(client.fetchWhitelist(OptionalLong.of(12))));
    }

    @ParameterizedTest
    @CsvSource({"200,false", "200,true", "503,false", "503,true"})
    void rejectsPolicyOneByteOverCapIncludingChunkedAndErrors(int responseStatus, boolean useChunks) throws Exception {
        status = responseStatus;
        chunked = useChunks;
        body = padded("{\"outcome\":\"licensed\"}", 1_048_577);
        assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy("Player")));
    }

    @ParameterizedTest
    @CsvSource({"200,false", "200,true", "503,false", "503,true"})
    void rejectsWhitelistOneByteOverCapIncludingChunkedAndErrors(int responseStatus, boolean useChunks) throws Exception {
        status = responseStatus;
        chunked = useChunks;
        body = padded("{\"outcome\":\"snapshot\",\"whitelist_version\":99,\"identities\":[]}", 1_048_577);
        assertEquals(new WhitelistResult.Unavailable(), await(client.fetchWhitelist(OptionalLong.of(12))));
    }

    @ParameterizedTest
    @CsvSource({"200,false", "200,true", "503,false", "503,true"})
    void cancelsOversizedStreamBeforeEndOrParsing(int responseStatus, boolean whitelist) throws Exception {
        status = responseStatus;
        chunked = true;
        body = padded(whitelist
                ? "{\"outcome\":\"snapshot\",\"whitelist_version\":99,\"identities\":[]}"
                : "{\"outcome\":\"licensed\"}", 1_048_577);
        bodySent = new CountDownLatch(1);
        bodyEndGate = new CountDownLatch(1);
        CompletionStage<?> result = whitelist
                ? client.fetchWhitelist(OptionalLong.of(12)) : client.fetchPolicy("Player");
        assertTrue(bodySent.await(2, TimeUnit.SECONDS), "Oversized prefix must reach the transport");
        Object value = assertTimeoutPreemptively(java.time.Duration.ofMillis(1500), () -> await(result));
        assertEquals(whitelist ? new WhitelistResult.Unavailable() : PolicyResult.UNAVAILABLE, value);
        assertEquals(1, bodyEndGate.getCount(), "Must reject before end-of-body, not after buffering/parsing");
    }

    private static String padded(String json, int bytes) {
        return json + " ".repeat(bytes - json.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test void overflowDoesNotPreventSubsequentSuccessfulReads() throws Exception {
        chunked = true;
        body = padded("{\"outcome\":\"licensed\"}", 1_048_577);
        assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy("Player")));
        body = "{\"outcome\":\"licensed\"}";
        assertEquals(PolicyResult.LICENSED, await(client.fetchPolicy("Player")));
    }

    @ParameterizedTest
    @CsvSource({"200,false", "200,true", "503,false", "503,true"})
    void oversizedWhitelistPreservesMembershipAndCursor(int responseStatus, boolean useChunks) throws Exception {
        var pending = new LinkedBlockingQueue<CompletableFuture<WhitelistResult>>();
        var cursors = new LinkedBlockingQueue<OptionalLong>();
        GameAuthClient controlled = new GameAuthClient() {
            @Override public CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] password) {
                if (password != null) java.util.Arrays.fill(password, '\0');
                return CompletableFuture.completedFuture(new CrackedLoginResult.Unavailable());
            }
            @Override public CompletionStage<SessionCheckResult> checkSession(String lease) {
                return CompletableFuture.completedFuture(new SessionCheckResult.Unavailable());
            }
            @Override public CompletionStage<PolicyResult> fetchPolicy(String nickname) { throw new AssertionError(); }
            @Override public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) {
                cursors.add(cursor);
                var result = new CompletableFuture<WhitelistResult>();
                pending.add(result);
                return result;
            }
            @Override public void close() {}
        };
        UUID existing = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaperWhitelist preserved = new PaperWhitelist() {
            @Override public Set<UUID> currentUuids() { return Set.of(existing); }
            @Override public void replaceWith(Collection<WhitelistIdentity> identities) {
                fail("An oversized response must never reach Paper setters");
            }
        };
        try (var sync = new WhitelistSynchronizer(controlled, preserved, java.time.Duration.ofHours(1),
                task -> fail("An oversized response must not dispatch any Paper work"))) {
            sync.syncNow();
            pending.remove().complete(new WhitelistResult.Unchanged(12));
            assertEquals(OptionalLong.empty(), cursors.remove());
            sync.syncNow();
            assertEquals(OptionalLong.of(12), cursors.remove());
            status = responseStatus;
            chunked = useChunks;
            body = padded("{\"outcome\":\"snapshot\",\"whitelist_version\":99,\"identities\":[]}", 1_048_577);
            // feed the real HTTP result into the real synchronizer
            pending.remove().complete(await(client.fetchWhitelist(OptionalLong.of(12))));
            assertEquals(Set.of(existing), preserved.currentUuids());
            sync.syncNow();
            assertEquals(OptionalLong.of(12), cursors.remove());
        }
    }

    @Test
    void rejectsMalformedNicknamesWithoutSendingRequests() throws Exception {
        for (String nickname : new String[] {null, "", "  ", "Player\nOne", "\u0000", "\ud800"}) {
            assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy(nickname)));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void transportFailureCompletesWithUnavailableRatherThanException() throws Exception {
        server.stop(0);
        assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy("Player")));
    }

    @Test
    void closedClientCompletesWithUnavailableWithoutSendingRequests() throws Exception {
        client.close();
        client.close();
        assertEquals(PolicyResult.UNAVAILABLE, await(client.fetchPolicy("Player")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void policyFetchIsAsynchronousWhileResponseIsBlocked() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        CompletionStage<PolicyResult> result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                () -> client.fetchPolicy("Player"));
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "Request should reach loopback");
        assertFalse(result.toCompletableFuture().isDone());
        responseGate.countDown();
        assertEquals(PolicyResult.LICENSED, await(result));
    }

    @Test
    void requestTimeoutBecomesUnavailable() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        CompletionStage<PolicyResult> result = client.fetchPolicy("Player");
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "Request should reach loopback");
        assertEquals(PolicyResult.UNAVAILABLE, result.toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeCompletesInflightAndQueuedRequestsWithoutException() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        CompletionStage<PolicyResult> inflight = client.fetchPolicy("Player");
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "Request should reach loopback");
        java.util.List<CompletionStage<PolicyResult>> pending = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) pending.add(client.fetchPolicy("Queued"));
        client.close();
        assertEquals(PolicyResult.UNAVAILABLE, await(inflight));
        for (CompletionStage<PolicyResult> result : pending) {
            assertEquals(PolicyResult.UNAVAILABLE, await(result));
        }
    }

    @Test
    void saturatedExecutorRejectsExcessWorkFailClosedWithoutBlockingCaller() throws Exception {
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        client.fetchPolicy("Player");
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "Request should reach loopback");
        java.util.List<CompletionStage<PolicyResult>> results = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(1), () -> {
                    java.util.List<CompletionStage<PolicyResult>> submitted = new java.util.ArrayList<>();
                    for (int index = 0; index < 100; index++) submitted.add(client.fetchPolicy("Queued"));
                    return submitted;
                });
        assertTrue(results.stream().anyMatch(result -> result.toCompletableFuture().isDone()),
                "Bounded queue must fail closed when saturated");
        for (CompletionStage<PolicyResult> result : results) {
            if (result.toCompletableFuture().isDone()) assertEquals(PolicyResult.UNAVAILABLE, await(result));
        }
    }

    @Test
    void whitelistWithoutCursorSendsExactRequestAndAcceptsUnchanged() throws Exception {
        body = "{\"outcome\":\"unchanged\",\"whitelist_version\":42}";
        assertEquals(new WhitelistResult.Unchanged(42), await(client.fetchWhitelist(OptionalLong.empty())));
        Request request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/internal/game-auth/v1/whitelist", request.target());
        assertTrue(request.authorized(), "Expected synthetic bearer authorization");
        assertEquals("application/json", request.accept());
        assertEquals("no-store", request.cacheControl());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 42, -42, Long.MAX_VALUE, Long.MIN_VALUE})
    void sendsExactSignedLongCursor(long cursor) throws Exception {
        body = "{\"outcome\":\"unchanged\",\"whitelist_version\":42}";
        assertEquals(new WhitelistResult.Unchanged(42), await(client.fetchWhitelist(OptionalLong.of(cursor))));
        assertEquals("/internal/game-auth/v1/whitelist?cursor=" + cursor, requests.remove().target());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 42, Long.MAX_VALUE})
    void acceptsAuthoritativeEmptySnapshot(long version) throws Exception {
        body = "{\"outcome\":\"snapshot\",\"whitelist_version\":" + version + ",\"identities\":[]}";
        assertEquals(new WhitelistResult.Snapshot(version, List.of()),
                await(client.fetchWhitelist(OptionalLong.empty())));
    }

    @Test
    void parsesCompleteSnapshotWithBothClientTypesAndApiUuids() throws Exception {
        body = """
                {"outcome":"snapshot","whitelist_version":42,"identities":[
                  {"nickname":"Player","client_type":"cracked","uuid":"a01e3843-e521-3998-958a-f459800e4d11"},
                  {"nickname":"Other","client_type":"licensed","uuid":"79b4b379-f25f-4c2a-8368-0f28fa96f249"}
                ]}
                """;
        WhitelistResult.Snapshot snapshot = assertInstanceOf(WhitelistResult.Snapshot.class,
                await(client.fetchWhitelist(OptionalLong.empty())));
        assertEquals(new WhitelistResult.Snapshot(42, List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, UUID.fromString("a01e3843-e521-3998-958a-f459800e4d11")),
                new WhitelistIdentity("Other", ClientType.LICENSED, UUID.fromString("79b4b379-f25f-4c2a-8368-0f28fa96f249")))), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.identities().clear());
    }

    @Test
    void snapshotOwnsAnImmutableCopyOfIdentities() {
        List<WhitelistIdentity> identities = new ArrayList<>(List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, UUID.fromString("a01e3843-e521-3998-958a-f459800e4d11"))));
        WhitelistResult.Snapshot snapshot = new WhitelistResult.Snapshot(42, identities);
        identities.clear();
        assertEquals(1, snapshot.identities().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.identities().clear());
    }

    @ParameterizedTest(name = "invalid whitelist envelope {index}")
    @ValueSource(strings = {
        "", "{", "null", "[]", "42", "{}",
        "{\"outcome\":\"unknown\",\"whitelist_version\":42}",
        "{\"outcome\":\"unchanged\"}",
        "{\"outcome\":null,\"whitelist_version\":42}",
        "{\"outcome\":1,\"whitelist_version\":42}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":-1}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":42.0}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":\"42\"}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":null}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":true}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":9223372036854775808}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":42,\"extra\":true}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":42,\"identities\":[]}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":42,\"whitelist_version\":43}",
        "{\"outcome\":\"unchanged\",\"whitelist_version\":42} {}",
        "{\"outcome\":\"snapshot\",\"whitelist_version\":42}",
        "{\"outcome\":\"snapshot\",\"whitelist_version\":-1,\"identities\":[]}",
        "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":null}",
        "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":{}}",
        "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":[null]}",
        "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":[],\"extra\":true}"
    })
    void rejectsMalformedOrNonExactWhitelistEnvelopes(String response) throws Exception {
        body = response;
        assertWhitelistUnavailableAfterRequest();
    }

    @ParameterizedTest(name = "invalid whitelist identity {index}")
    @ValueSource(strings = {
        "{}", "null", "[]", "42",
        "{\"nickname\":\"Player\",\"client_type\":\"cracked\",\"uuid\":\"invalid\"}",
        "{\"nickname\":\"Player\",\"client_type\":\"cracked\",\"uuid\":\"1-1-1-1-1\"}",
        "{\"nickname\":\"Player\",\"client_type\":\"unknown\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":\"Player\",\"client_type\":\"CRACKED\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":\"Player\",\"client_type\":null,\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":\"Player\",\"client_type\":\"cracked\"}",
        "{\"nickname\":\"Player\",\"client_type\":\"cracked\",\"uuid\":null}",
        "{\"nickname\":null,\"client_type\":\"cracked\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":1,\"client_type\":\"cracked\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":\"\",\"client_type\":\"cracked\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":\"Player\\nOne\",\"client_type\":\"cracked\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\"}",
        "{\"nickname\":\"Player\",\"client_type\":\"cracked\",\"uuid\":\"a01e3843-e521-3998-958a-f459800e4d11\",\"extra\":true}"
    })
    void rejectsInvalidOrNonExactIdentities(String identity) throws Exception {
        body = "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":[" + identity + "]}";
        assertWhitelistUnavailableAfterRequest();
    }

    @ParameterizedTest(name = "duplicate snapshot identity {index}")
    @CsvSource({
        "Other,a01e3843-e521-3998-958a-f459800e4d11",
        "Player,79b4b379-f25f-4c2a-8368-0f28fa96f249",
        "player,79b4b379-f25f-4c2a-8368-0f28fa96f249"
    })
    void rejectsDuplicateUuidOrCaseInsensitiveNickname(String nickname, String uuid) throws Exception {
        body = """
                {"outcome":"snapshot","whitelist_version":42,"identities":[
                  {"nickname":"Player","client_type":"cracked","uuid":"a01e3843-e521-3998-958a-f459800e4d11"},
                  {"nickname":"%s","client_type":"licensed","uuid":"%s"}
                ]}
                """.formatted(nickname, uuid);
        assertWhitelistUnavailableAfterRequest();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 301, 302, 307, 400, 401, 403, 404, 429, 500, 503})
    void whitelistNon200IsUnavailableWithoutFollowingRedirects(int responseStatus) throws Exception {
        body = "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":[]}";
        status = responseStatus;
        assertWhitelistUnavailableAfterRequest();
        assertTrue(requests.isEmpty(), "Redirects must not issue another request");
    }

    @Test
    void whitelistTransportFailureIsUnavailable() throws Exception {
        server.stop(0);
        assertInstanceOf(WhitelistResult.Unavailable.class, await(client.fetchWhitelist(OptionalLong.empty())));
    }

    @Test
    void closedClientDoesNotFetchWhitelist() throws Exception {
        client.close();
        assertInstanceOf(WhitelistResult.Unavailable.class, await(client.fetchWhitelist(OptionalLong.of(42))));
        assertTrue(requests.isEmpty());
    }

    @Test
    void whitelistTimeoutIsUnavailableAndCallDoesNotBlock() throws Exception {
        body = "{\"outcome\":\"snapshot\",\"whitelist_version\":42,\"identities\":[]}";
        responseGate = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        CompletionStage<WhitelistResult> result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                () -> client.fetchWhitelist(OptionalLong.empty()));
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS), "Request should reach loopback");
        assertFalse(result.toCompletableFuture().isDone());
        assertInstanceOf(WhitelistResult.Unavailable.class, result.toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void packagedClientWorksWithoutGradleDependencyClasspath() throws Exception {
        Path jar = Path.of(System.getProperty("pierdolAuthJar"));
        try (URLClassLoader isolated = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = isolated.loadClass("dev.sccl.pierdolauth.api.HttpGameAuthClient");
            try (AutoCloseable packaged = (AutoCloseable) type.getConstructor(URI.class, Path.class)
                    .newInstance(baseUrl(), temporary.resolve("credential"))) {
                CompletionStage<?> result = (CompletionStage<?>) type.getMethod("fetchPolicy", String.class)
                        .invoke(packaged, "Player");
                assertEquals("LICENSED", await(result).toString());
            }
        }
    }

    private void assertWhitelistUnavailableAfterRequest() throws Exception {
        assertInstanceOf(WhitelistResult.Unavailable.class, await(client.fetchWhitelist(OptionalLong.empty())));
        Request request = requests.poll();
        assertNotNull(request, "Whitelist response must have been requested");
        assertEquals("/internal/game-auth/v1/whitelist", request.target());
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/game-auth/v1");
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (exchange.getRequestMethod().equals("POST")) {
                var requestBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(exchange.getRequestBody());
                exactPostBody = exchange.getRequestURI().getPath().endsWith("/cracked-login")
                        ? requestBody.size() == 2 && requestBody.path("nickname").asText().equals("Player")
                                && expectedPassword != null
                                && java.util.Arrays.equals(expectedPassword, requestBody.path("password").asText().toCharArray())
                        : requestBody.size() == 1 && syntheticLease.equals(requestBody.path("lease").asText());
            }
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                    "Bearer credential-sentinel".equals(exchange.getRequestHeaders().getFirst("Authorization")),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Cache-Control")));
            if (requestReceived != null) requestReceived.countDown();
            if (responseGate != null) {
                try {
                    responseGate.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", baseUrl() + "/redirect-target");
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, chunked ? 0 : response.length == 0 ? -1 : response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().flush();
                if (bodySent != null) bodySent.countDown();
                if (bodyEndGate != null) {
                    try {
                        bodyEndGate.await(8, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(6, TimeUnit.SECONDS);
    }

    private record Request(String method, String target, boolean authorized, String accept, String cacheControl) {}
}
