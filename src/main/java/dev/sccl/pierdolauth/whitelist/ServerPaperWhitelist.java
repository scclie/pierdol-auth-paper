package dev.sccl.pierdolauth.whitelist;

import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;

/** Uses only public UUID-based Paper whitelist operations, on the caller's main thread. */
public final class ServerPaperWhitelist implements PaperWhitelist {
    private final Server server;

    public ServerPaperWhitelist(Server server) {
        this.server = server;
    }

    @Override public Set<UUID> currentUuids() {
        return server.getWhitelistedPlayers().stream()
                .map(OfflinePlayer::getUniqueId).collect(Collectors.toUnmodifiableSet());
    }

    @Override public void replaceWith(Collection<WhitelistIdentity> identities) {
        Map<UUID, OfflinePlayer> previous = new LinkedHashMap<>();
        for (OfflinePlayer player : server.getWhitelistedPlayers()) {
            previous.put(player.getUniqueId(), player);
        }
        // resolve every UUID before mutating
        Map<UUID, OfflinePlayer> desired = new LinkedHashMap<>();
        for (WhitelistIdentity identity : identities) {
            desired.computeIfAbsent(identity.uuid(), server::getOfflinePlayer);
        }
        try {
            for (var entry : previous.entrySet()) {
                if (!desired.containsKey(entry.getKey())) entry.getValue().setWhitelisted(false);
            }
            for (OfflinePlayer player : desired.values()) player.setWhitelisted(true);
        } catch (RuntimeException failure) {
            // paper has no atomic replacement api - restore orig membership if a setter fails.
            Map<UUID, OfflinePlayer> affected = new LinkedHashMap<>(desired);
            affected.putAll(previous);
            for (var entry : affected.entrySet()) {
                try {
                    entry.getValue().setWhitelisted(previous.containsKey(entry.getKey()));
                } catch (RuntimeException ignored) {
                    // best effort only: another paper failure can also prevent rollback
                }
            }
            throw failure;
        }
    }
}
