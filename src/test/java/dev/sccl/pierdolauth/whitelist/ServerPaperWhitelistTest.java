package dev.sccl.pierdolauth.whitelist;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class ServerPaperWhitelistTest {
    private static final UUID STALE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CRACKED = UUID.fromString("a01e3843-e521-3998-958a-f459800e4d11");
    private static final UUID LICENSED = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final List<WhitelistIdentity> IDENTITIES = List.of(
            new WhitelistIdentity("NotAnIdentityLookup", ClientType.CRACKED, CRACKED),
            new WhitelistIdentity("AlsoMetadataOnly", ClientType.LICENSED, LICENSED));
    private final SyntheticServer server = new SyntheticServer();
    private final ServerPaperWhitelist whitelist = new ServerPaperWhitelist(server);

    @Test void populatedReplacementUsesOnlyApiUuidsAndRemovesStaleFirst() {
        server.member(STALE);
        server.member(LICENSED);
        assertEquals(Set.of(STALE, LICENSED), whitelist.currentUuids());
        whitelist.replaceWith(IDENTITIES);
        assertEquals(Set.of(CRACKED, LICENSED), server.members());
        assertEquals(Set.of(CRACKED, LICENSED), whitelist.currentUuids());
        assertEquals(List.of("resolve:" + CRACKED, "resolve:" + LICENSED,
                "set:" + STALE + ":false", "set:" + CRACKED + ":true", "set:" + LICENSED + ":true"), server.events);
    }

    @Test void emptyReplacementClearsAllMembersWithoutResolving() {
        server.member(STALE);
        server.member(CRACKED);
        whitelist.replaceWith(List.of());
        assertEquals(Set.of(), server.members());
        assertEquals(List.of("set:" + STALE + ":false", "set:" + CRACKED + ":false"), server.events);
    }

    @Test void resolutionFailureOccursBeforeAnySetterMutation() {
        server.member(STALE);
        RuntimeException failure = new IllegalStateException("synthetic resolution failure");
        server.resolutionFailure = failure;
        assertSame(failure, assertThrows(RuntimeException.class, () -> whitelist.replaceWith(IDENTITIES)));
        assertEquals(Set.of(STALE), server.members());
        assertEquals(List.of("resolve:" + CRACKED, "resolve:" + LICENSED), server.events);
    }

    @Test void setterFailureAfterRemovalAndAdditionRestoresOriginalMembership() {
        server.member(STALE);
        RuntimeException failure = new IllegalStateException("synthetic setter failure");
        server.player(LICENSED).enableFailure = failure;
        assertSame(failure, assertThrows(RuntimeException.class, () -> whitelist.replaceWith(IDENTITIES)));
        assertEquals(Set.of(STALE), server.members());
        assertEquals(List.of("resolve:" + CRACKED, "resolve:" + LICENSED,
                "set:" + STALE + ":false", "set:" + CRACKED + ":true", "set:" + LICENSED + ":true",
                "set:" + CRACKED + ":false", "set:" + LICENSED + ":false", "set:" + STALE + ":true"), server.events);
    }

    @Test void rollbackFailureDoesNotReplaceOriginalFailureOrPreventLaterRestoration() {
        server.member(STALE);
        RuntimeException original = new IllegalStateException("synthetic original failure");
        server.player(LICENSED).enableFailure = original;
        server.player(CRACKED).disableFailure = new IllegalArgumentException("synthetic rollback failure");
        assertSame(original, assertThrows(RuntimeException.class, () -> whitelist.replaceWith(IDENTITIES)));
        assertEquals(Set.of(STALE, CRACKED), server.members(), "Rollback is best effort, not a transaction");
        assertTrue(server.events.contains("set:" + LICENSED + ":false"));
        assertEquals("set:" + STALE + ":true", server.events.getLast());
    }

    private static final class SyntheticServer extends UnsupportedPaperDoubles.Server {
        private final Map<UUID, SyntheticPlayer> players = new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();
        private RuntimeException resolutionFailure;

        SyntheticPlayer player(UUID uuid) {
            return players.computeIfAbsent(uuid, value -> new SyntheticPlayer(value, events));
        }

        void member(UUID uuid) { player(uuid).whitelisted = true; }

        Set<UUID> members() {
            Set<UUID> members = new LinkedHashSet<>();
            players.forEach((uuid, player) -> { if (player.whitelisted) members.add(uuid); });
            return members;
        }

        @Override public Set<OfflinePlayer> getWhitelistedPlayers() {
            Set<OfflinePlayer> members = new LinkedHashSet<>();
            players.values().forEach(player -> { if (player.whitelisted) members.add(player); });
            return members;
        }

        @Override public OfflinePlayer getOfflinePlayer(UUID uuid) {
            events.add("resolve:" + uuid);
            if (uuid.equals(LICENSED) && resolutionFailure != null) throw resolutionFailure;
            return player(uuid);
        }
    }

    private static final class SyntheticPlayer extends UnsupportedPaperDoubles.OfflinePlayer {
        private final UUID uuid;
        private final List<String> events;
        private boolean whitelisted;
        private RuntimeException enableFailure;
        private RuntimeException disableFailure;

        SyntheticPlayer(UUID uuid, List<String> events) {
            this.uuid = uuid;
            this.events = events;
        }

        @Override public UUID getUniqueId() { return uuid; }
        @Override public boolean isWhitelisted() { return whitelisted; }
        @Override public void setWhitelisted(boolean value) {
            events.add("set:" + uuid + ":" + value);
            if (value && enableFailure != null) throw enableFailure;
            if (!value && disableFailure != null) throw disableFailure;
            whitelisted = value;
        }
    }
}
