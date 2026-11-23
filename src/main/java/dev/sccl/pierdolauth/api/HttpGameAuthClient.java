package dev.sccl.pierdolauth.api;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sccl.pierdolauth.config.ApiCredential;
import dev.sccl.pierdolauth.config.ConfigurationException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class HttpGameAuthClient implements GameAuthClient {
    private final String baseUrl;
    private final ApiCredential credential;
    private final HttpClient http;
    private final ThreadPoolExecutor executor;
    private final Set<Runnable> pending = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private boolean closed;

    public HttpGameAuthClient(URI baseUrl, Path credentialFile) throws ConfigurationException {
        this.baseUrl = baseUrl.toASCIIString().replaceFirst("/$", "");
        credential = ApiCredential.open(credentialFile);
        try {
            http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32), runnable -> {
                        Thread thread = new Thread(runnable, "game-auth-http");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        } catch (RuntimeException failure) {
            credential.close();
            throw new ConfigurationException();
        }
    }

    @Override
    public CompletionStage<PolicyResult> fetchPolicy(String nickname) {
        if (!validNickname(nickname)) return CompletableFuture.completedFuture(PolicyResult.UNAVAILABLE);
        String encoded = URLEncoder.encode(nickname, StandardCharsets.UTF_8).replace("+", "%20");
        return request("GET", "/identity-policy?nickname=" + encoded, null, this::parsePolicy, PolicyResult.UNAVAILABLE);
    }

    @Override
    public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) {
        String query = cursor.isPresent() ? "?cursor=" + cursor.getAsLong() : "";
        return request("GET", "/whitelist" + query, null, this::parseWhitelist, new WhitelistResult.Unavailable());
    }

    @Override
    public CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] password) {
        var unavailable = new CrackedLoginResult.Unavailable();
        try {
            if (!validNickname(nickname) || password == null) return CompletableFuture.completedFuture(unavailable);
            byte[] bytes;
            try (var output = new SensitiveOutput(); var generator = json.createGenerator(output)) {
                generator.writeStartObject();
                generator.writeStringField("nickname", nickname);
                generator.writeFieldName("password");
                generator.writeString(password, 0, password.length);
                generator.writeEndObject();
                generator.flush();
                bytes = output.toByteArray();
            }
            return request("POST", "/cracked-login", bytes, this::parseCrackedLogin, unavailable);
        } catch (Exception ignored) {
            return CompletableFuture.completedFuture(unavailable);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    @Override
    public CompletionStage<SessionCheckResult> checkSession(String lease) {
        var unavailable = new SessionCheckResult.Unavailable();
        if (!validLease(lease)) return CompletableFuture.completedFuture(unavailable);
        try {
            byte[] bytes = json.writeValueAsBytes(java.util.Map.of("lease", lease));
            return request("POST", "/session-check", bytes, this::parseSessionCheck, unavailable);
        } catch (Exception ignored) {
            return CompletableFuture.completedFuture(unavailable);
        }
    }

    @Override
    public CompletionStage<CrackedLoginResult> premiumVerified(String nickname, UUID mojangUuid) {
        var unavailable = new CrackedLoginResult.Unavailable();
        if (!validNickname(nickname) || mojangUuid == null) return CompletableFuture.completedFuture(unavailable);
        try {
            byte[] bytes = json.writeValueAsBytes(java.util.Map.of(
                    "nickname", nickname, "mojang_uuid", mojangUuid.toString()));
            return request("POST", "/premium-verified", bytes, this::parseCrackedLogin, unavailable);
        } catch (Exception ignored) {
            return CompletableFuture.completedFuture(unavailable);
        }
    }

    @Override
    public CompletionStage<Boolean> creditActiveMinutes(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > 100) {
            return CompletableFuture.completedFuture(false);
        }
        Set<Long> seen = new HashSet<>();
        StringBuilder body = new StringBuilder("{\"user_ids\":[");
        for (Long id : userIds) {
            if (id == null || id <= 0 || !seen.add(id) || seen.size() > 100) {
                return CompletableFuture.completedFuture(false);
            }
            if (seen.size() > 1) body.append(',');
            body.append(id);
        }
        if (seen.isEmpty()) return CompletableFuture.completedFuture(false);
        byte[] bytes = body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        return request("POST", "/active-minutes", bytes, ignored -> true, false, 204);
    }

    private CrackedLoginResult parseCrackedLogin(JsonNode value) {
        if (negativeEnvelope(value, "deny")) return new CrackedLoginResult.Denied();
        if (!positiveEnvelope(value, "allow")) return new CrackedLoginResult.Unavailable();
        return new CrackedLoginResult.Allowed(UUID.fromString(value.get("uuid").textValue()),
                value.get("lease").textValue(), value.get("expires_at").longValue(), value.get("user_id").longValue());
    }

    private SessionCheckResult parseSessionCheck(JsonNode value) {
        if (negativeEnvelope(value, "disconnect")) return new SessionCheckResult.Disconnected();
        if (!positiveEnvelope(value, "continue")) return new SessionCheckResult.Unavailable();
        return new SessionCheckResult.Continued(UUID.fromString(value.get("uuid").textValue()),
                value.get("lease").textValue(), value.get("expires_at").longValue(), value.get("user_id").longValue());
    }

    private static boolean negativeEnvelope(JsonNode value, String outcome) {
        return value != null && value.isObject() && value.size() == 2
                && outcome.equals(value.path("outcome").textValue())
                && nonnegativeLong(value.path("whitelist_version"));
    }

    private static boolean positiveEnvelope(JsonNode value, String outcome) {
        if (value == null || !value.isObject() || value.size() != 6
                || !outcome.equals(value.path("outcome").textValue())
                || !nonnegativeLong(value.path("whitelist_version"))
                || !nonnegativeLong(value.path("expires_at"))
                || !nonnegativeLong(value.path("user_id")) || value.path("user_id").longValue() == 0
                || !validLease(value.path("lease").textValue())
                || !value.path("uuid").isTextual()) return false;
        String text = value.get("uuid").textValue();
        return UUID.fromString(text).toString().equalsIgnoreCase(text);
    }

    private static boolean nonnegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private static boolean validLease(String lease) {
        if (lease == null || lease.length() != 64) return false;
        for (int index = 0; index < lease.length(); index++) {
            char value = lease.charAt(index);
            if (!(value >= '0' && value <= '9') && !(value >= 'a' && value <= 'f')
                    && !(value >= 'A' && value <= 'F')) return false;
        }
        return true;
    }

    private static final class SensitiveOutput extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); }
    }

    private WhitelistResult parseWhitelist(JsonNode value) {
        WhitelistResult unavailable = new WhitelistResult.Unavailable();
        if (!value.isObject() || !value.path("outcome").isTextual()) return unavailable;
        JsonNode version = value.path("whitelist_version");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
            return unavailable;
        }
        String outcome = value.get("outcome").textValue();
        if ("unchanged".equals(outcome) && value.size() == 2) {
            return new WhitelistResult.Unchanged(version.longValue());
        }
        if (!"snapshot".equals(outcome) || value.size() != 3 || !value.path("identities").isArray()) {
            return unavailable;
        }
        List<WhitelistIdentity> identities = new ArrayList<>();
        Set<UUID> uuids = new HashSet<>();
        Set<String> nicknames = new HashSet<>();
        for (JsonNode identity : value.get("identities")) {
            if (!identity.isObject() || identity.size() != 3
                    || !identity.path("nickname").isTextual()
                    || !identity.path("client_type").isTextual()
                    || !identity.path("uuid").isTextual()) return unavailable;
            String nickname = identity.get("nickname").textValue();
            if (!validNickname(nickname)) return unavailable;
            ClientType type = switch (identity.get("client_type").textValue()) {
                case "cracked" -> ClientType.CRACKED;
                case "licensed" -> ClientType.LICENSED;
                default -> null;
            };
            if (type == null) return unavailable;
            String uuidText = identity.get("uuid").textValue();
            UUID uuid = UUID.fromString(uuidText);
            if (!uuid.toString().equalsIgnoreCase(uuidText)
                    || !uuids.add(uuid) || !nicknames.add(nickname.toLowerCase(Locale.ROOT))) return unavailable;
            identities.add(new WhitelistIdentity(nickname, type, uuid));
        }
        return new WhitelistResult.Snapshot(version.longValue(), identities);
    }

    private PolicyResult parsePolicy(JsonNode value) {
        if (!value.isObject() || value.size() != 1 || !value.path("outcome").isTextual()) {
            return PolicyResult.UNAVAILABLE;
        }
        return switch (value.get("outcome").textValue()) {
            case "denied" -> PolicyResult.DENIED;
            case "cracked" -> PolicyResult.CRACKED;
            case "licensed" -> PolicyResult.LICENSED;
            default -> PolicyResult.UNAVAILABLE;
        };
    }

    private synchronized <T> CompletionStage<T> request(String method, String path, byte[] bytes,
            Function<JsonNode, T> parser, T unavailable) {
        return request(method, path, bytes, parser, unavailable, 200);
    }

    private synchronized <T> CompletionStage<T> request(String method, String path, byte[] bytes,
            Function<JsonNode, T> parser, T unavailable, int successStatus) {
        if (closed) {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            return CompletableFuture.completedFuture(unavailable);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        java.util.function.Consumer<T> complete = value -> {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            result.complete(value);
        };
        Runnable cancel = () -> complete.accept(unavailable);
        pending.add(cancel);
        result.whenComplete((value, failure) -> {
            pending.remove(cancel);
        });
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else {
                builder.GET();
            }
            credential.authorize(builder);
            HttpRequest request = builder.build();
            executor.execute(() -> {
                try {
                    HttpResponse<byte[]> response = http.send(request, new BoundedResponseBodyHandler());
                    complete.accept(response.statusCode() == successStatus
                            ? parser.apply(successStatus == 204 ? null : json.readTree(response.body())) : unavailable);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    complete.accept(unavailable);
                } catch (Exception ignored) {
                    complete.accept(unavailable);
                }
            });
        } catch (RuntimeException ignored) {
            complete.accept(unavailable);
        }
        // keep terminal authority with transport/close: public future conversions are detached, so caller mutation cannot wipe bytes still owned by a send.
        return result.minimalCompletionStage();
    }

    private static boolean validNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return false;
        for (int index = 0; index < nickname.length(); index++) {
            char value = nickname.charAt(index);
            if (Character.isISOControl(value) || Character.isLowSurrogate(value)) return false;
            if (Character.isHighSurrogate(value)
                    && (++index == nickname.length() || !Character.isLowSurrogate(nickname.charAt(index)))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        credential.close();
        http.shutdownNow();
        executor.shutdownNow();
        pending.forEach(Runnable::run);
        pending.clear();
    }
}
