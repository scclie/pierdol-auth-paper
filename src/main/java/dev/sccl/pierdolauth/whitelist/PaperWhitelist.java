package dev.sccl.pierdolauth.whitelist;

import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Main-thread-only access to API-owned UUID membership, not persisted display names. */
public interface PaperWhitelist {
    Set<UUID> currentUuids();
    void replaceWith(Collection<WhitelistIdentity> identities);
}
