package dev.sccl.pierdolauth.api;

import java.util.Collection;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

public interface AuthenticatedPlayerService {
    OptionalLong authenticatedUserId(Player player);
    CompletionStage<Boolean> creditActiveMinutes(Collection<Long> userIds);
    int maxCreditBatchSize();
}
