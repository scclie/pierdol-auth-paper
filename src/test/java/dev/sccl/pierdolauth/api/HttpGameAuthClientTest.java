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
            // Feed the real HTTP result into the real synchronizer, deterministically on this thread.
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
