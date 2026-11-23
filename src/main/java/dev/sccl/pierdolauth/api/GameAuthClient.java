package dev.sccl.pierdolauth.api;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;

import java.util.OptionalLong;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface GameAuthClient extends AutoCloseable {
    CompletionStage<PolicyResult> fetchPolicy(String nickname);
    CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor);
    // ownership of the mutable input transfers to the client at method invocation, even if it throws synchronously. the caller must never mutate it afterward
    CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] password);
    CompletionStage<SessionCheckResult> checkSession(String lease);
    default CompletionStage<CrackedLoginResult> premiumVerified(String nickname, UUID mojangUuid) {
        return CompletableFuture.completedFuture(new CrackedLoginResult.Unavailable());
    }
    default CompletionStage<Boolean> creditActiveMinutes(Collection<Long> userIds) {
        return CompletableFuture.completedFuture(false);
    }
    @Override void close();
}
