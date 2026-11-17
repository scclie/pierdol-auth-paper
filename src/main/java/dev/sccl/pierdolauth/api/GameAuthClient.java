package dev.sccl.pierdolauth.api;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;

import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

public interface GameAuthClient extends AutoCloseable {
    CompletionStage<PolicyResult> fetchPolicy(String nickname);
    CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor);
    // ownership of the mutable input transfers to the client at method invocation, even if it throws synchronously. the caller must never mutate it afterward
    CompletionStage<CrackedLoginResult> crackedLogin(String nickname, char[] password);
    CompletionStage<SessionCheckResult> checkSession(String lease);
    @Override void close();
}
