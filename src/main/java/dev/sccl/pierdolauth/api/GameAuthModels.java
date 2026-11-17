package dev.sccl.pierdolauth.api;

import java.util.List;
import java.util.UUID;

public final class GameAuthModels {
    private GameAuthModels() {}

    public enum PolicyResult { DENIED, CRACKED, LICENSED, UNAVAILABLE }

    public sealed interface CrackedLoginResult {
        record Allowed(UUID uuid, String lease, long expiresAtEpochSeconds) implements CrackedLoginResult {
            @Override public String toString() { return "Allowed[redacted]"; }
        }
        record Denied() implements CrackedLoginResult {}
        record Unavailable() implements CrackedLoginResult {}
    }

    public sealed interface SessionCheckResult {
        record Continued(UUID uuid, String lease, long expiresAtEpochSeconds) implements SessionCheckResult {
            @Override public String toString() { return "Continued[redacted]"; }
        }
        record Disconnected() implements SessionCheckResult {}
        record Unavailable() implements SessionCheckResult {}
    }

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
