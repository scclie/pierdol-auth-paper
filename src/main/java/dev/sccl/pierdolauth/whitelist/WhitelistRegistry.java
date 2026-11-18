package dev.sccl.pierdolauth.whitelist;

import dev.sccl.pierdolauth.api.GameAuthModels.ClientType;
import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class WhitelistRegistry {
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(false, Map.of(), Map.of(), Map.of()));

    public static final class Snapshot {
        private final boolean ready;
        private final Map<UUID, ClientType> clientTypes;
        private final Map<UUID, WhitelistIdentity> identities;
        private final Map<String, WhitelistIdentity> identitiesByNickname;

        private Snapshot(boolean ready, Map<UUID, ClientType> clientTypes,
                Map<UUID, WhitelistIdentity> identities,
                Map<String, WhitelistIdentity> identitiesByNickname) {
            this.ready = ready;
            this.clientTypes = Map.copyOf(clientTypes);
            this.identities = Map.copyOf(identities);
            this.identitiesByNickname = Map.copyOf(identitiesByNickname);
        }

        public boolean ready() {
            return ready;
        }

        public Map<UUID, ClientType> clientTypes() {
            return clientTypes;
        }

        public Map<UUID, WhitelistIdentity> identities() {
            return identities;
        }

        public WhitelistIdentity identity(UUID uuid) {
            return identities.get(uuid);
        }

        public WhitelistIdentity identity(String nickname) {
            return nickname == null ? null : identitiesByNickname.get(normalize(nickname));
        }
    }

    public Snapshot snapshot() {
        return current.get();
    }

    public void publish(List<WhitelistIdentity> identities) {
        current.set(prepare(identities));
    }

    public void publish(Snapshot snapshot) {
        current.set(Objects.requireNonNull(snapshot));
    }

    public void publish(Map<UUID, WhitelistIdentity> identities) {
        publish(List.copyOf(Objects.requireNonNull(identities).values()));
    }

    static Snapshot prepare(List<WhitelistIdentity> identities) {
        Map<UUID, WhitelistIdentity> identitiesByUuid = new HashMap<>();
        Map<String, WhitelistIdentity> identitiesByNickname = new HashMap<>();
        for (WhitelistIdentity identity : identities) {
            Objects.requireNonNull(identity);
            UUID uuid = Objects.requireNonNull(identity.uuid());
            Objects.requireNonNull(identity.clientType());
            String nickname = Objects.requireNonNull(identity.nickname());
            WhitelistIdentity previousByUuid = identitiesByUuid.putIfAbsent(uuid, identity);
            if (previousByUuid != null && previousByUuid.clientType() != identity.clientType()) {
                throw new IllegalArgumentException("Conflicting client types");
            }
            if (previousByUuid != null && !previousByUuid.equals(identity)) {
                throw new IllegalArgumentException("Conflicting identities");
            }
            WhitelistIdentity previousByNickname = identitiesByNickname.putIfAbsent(normalize(nickname), identity);
            if (previousByNickname != null && !previousByNickname.uuid().equals(uuid)) {
                throw new IllegalArgumentException("Ambiguous nickname");
            }
        }
        Map<UUID, ClientType> clientTypes = new HashMap<>();
        for (Map.Entry<UUID, WhitelistIdentity> entry : identitiesByUuid.entrySet()) {
            clientTypes.put(entry.getKey(), entry.getValue().clientType());
        }
        return new Snapshot(true, clientTypes, identitiesByUuid, identitiesByNickname);
    }

    private static String normalize(String nickname) {
        return nickname.toLowerCase(Locale.ROOT);
    }
}
