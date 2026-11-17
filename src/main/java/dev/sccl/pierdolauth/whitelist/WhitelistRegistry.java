package dev.sccl.pierdolauth.whitelist;

import dev.sccl.pierdolauth.api.GameAuthModels.ClientType;
import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class WhitelistRegistry {
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(false, Map.of()));

    public record Snapshot(boolean ready, Map<UUID, ClientType> clientTypes) {
        public Snapshot {
            clientTypes = Map.copyOf(clientTypes);
        }
    }

    public Snapshot snapshot() {
        return current.get();
    }

    public void publish(List<WhitelistIdentity> identities) {
        Map<UUID, ClientType> clientTypes = new HashMap<>();
        for (WhitelistIdentity identity : identities) {
            ClientType previous = clientTypes.putIfAbsent(Objects.requireNonNull(identity.uuid()),
                    Objects.requireNonNull(identity.clientType()));
            if (previous != null && previous != identity.clientType()) {
                throw new IllegalArgumentException("Conflicting client types");
            }
        }
        current.set(new Snapshot(true, clientTypes));
    }
}
