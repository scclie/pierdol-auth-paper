package dev.sccl.pierdolauth.api;

import java.util.List;
import java.util.UUID;

public final class GameAuthModels {
    private GameAuthModels() {}

    public enum PolicyResult { DENIED, CRACKED, LICENSED, UNAVAILABLE }

    public sealed interface WhitelistResult {
        record Unchanged(long version) implements WhitelistResult {}
        record Snapshot(long version, List<WhitelistIdentity> identities) implements WhitelistResult {
            public Snapshot {
                identities = List.copyOf(identities);
            }
        }
        record Unavailable() implements WhitelistResult {}
    }

    public record WhitelistIdentity(String nickname, ClientType clientType, UUID uuid) {}
    public enum ClientType { CRACKED, LICENSED }
}
