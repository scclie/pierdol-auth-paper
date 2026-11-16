package dev.sccl.pierdolauth.api;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sccl.pierdolauth.config.ApiCredential;
import dev.sccl.pierdolauth.config.ConfigurationException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.concurrent.RejectedExecutionException;
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
        return fetch("/identity-policy?nickname=" + encoded, this::parsePolicy, PolicyResult.UNAVAILABLE);
    }

    @Override
    public CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor) {
        String query = cursor.isPresent() ? "?cursor=" + cursor.getAsLong() : "";
        return fetch("/whitelist" + query, this::parseWhitelist, new WhitelistResult.Unavailable());
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

    private synchronized <T> CompletionStage<T> fetch(String path, Function<JsonNode, T> parser, T unavailable) {
        if (closed) return CompletableFuture.completedFuture(unavailable);
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable cancel = () -> result.complete(unavailable);
        pending.add(cancel);
        result.whenComplete((value, failure) -> pending.remove(cancel));
        try {
            executor.execute(() -> {
                try {
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(3))
                            .header("Accept", "application/json")
                            .header("Cache-Control", "no-store")
                            .GET();
                    credential.authorize(request);
                    HttpResponse<byte[]> response = http.send(request.build(), new BoundedResponseBodyHandler());
                    result.complete(response.statusCode() == 200
                            ? parser.apply(json.readTree(response.body())) : unavailable);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.complete(unavailable);
                } catch (Exception ignored) {
                    result.complete(unavailable);
                }
            });
        } catch (RejectedExecutionException ignored) {
            result.complete(unavailable);
        }
        return result;
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
