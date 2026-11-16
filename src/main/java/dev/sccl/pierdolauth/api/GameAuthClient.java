package dev.sccl.pierdolauth.api;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;

import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

public interface GameAuthClient extends AutoCloseable {
    CompletionStage<PolicyResult> fetchPolicy(String nickname);
    CompletionStage<WhitelistResult> fetchWhitelist(OptionalLong cursor);
    @Override void close();
}
