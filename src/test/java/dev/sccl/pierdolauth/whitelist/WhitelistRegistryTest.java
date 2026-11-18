package dev.sccl.pierdolauth.whitelist;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WhitelistRegistryTest {
    private static final UUID UUID_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UUID_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final WhitelistRegistry registry = new WhitelistRegistry();

    @Test void firstPublicationRetainsImmutableIdentitiesWithUuidAndCaseInsensitiveNicknameLookup() {
        var cracked = new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE);
        var licensed = new WhitelistIdentity("LiCeNsEd", ClientType.LICENSED, UUID_TWO);
        registry.publish(List.of(cracked, licensed));
        var snapshot = registry.snapshot();
        assertEquals(Map.of(UUID_ONE, cracked, UUID_TWO, licensed), snapshot.identities());
        assertSame(licensed, snapshot.identity(UUID_TWO));
        assertSame(licensed, snapshot.identity("licensed"));
        assertSame(licensed, snapshot.identity("LICENSED"));
        assertNull(snapshot.identity("missing"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.identities().put(UUID_TWO, cracked));
    }

    @Test void ambiguousNormalizedNicknamesRejectEntirePublication() {
        registry.publish(List.of(new WhitelistIdentity("Current", ClientType.CRACKED, UUID_ONE)));
        var previous = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.publish(List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE),
                new WhitelistIdentity("player", ClientType.LICENSED, UUID_TWO))));
        assertSame(previous, registry.snapshot());
    }

    @Test void firstPublicationMakesExactClientTypesReady() {
        assertFalse(registry.snapshot().ready());
        assertEquals(Map.of(), registry.snapshot().clientTypes());
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE),
                new WhitelistIdentity("Other", ClientType.LICENSED, UUID_TWO)));
        assertTrue(registry.snapshot().ready());
        assertEquals(Map.of(UUID_ONE, ClientType.CRACKED, UUID_TWO, ClientType.LICENSED),
                registry.snapshot().clientTypes());
    }

    @Test void emptyPublicationReplacesPreviousStateWithReadyUnmodifiableMap() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE)));
        var previous = registry.snapshot();
        registry.publish(List.of());
        assertTrue(registry.snapshot().ready());
        assertTrue(registry.snapshot().clientTypes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.snapshot().clientTypes().put(UUID_TWO, ClientType.LICENSED));
        assertEquals(Map.of(UUID_ONE, ClientType.CRACKED), previous.clientTypes());
    }

    @Test void callerMutationCannotChangePublishedState() {
        var identities = new ArrayList<>(List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE)));
        registry.publish(identities);
        identities.clear();
        assertEquals(Map.of(UUID_ONE, ClientType.CRACKED), registry.snapshot().clientTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.snapshot().clientTypes().clear());
    }

    @Test void conflictingDuplicateRejectsEntirePublicationAndPreservesPreviousSnapshot() {
        registry.publish(List.of(new WhitelistIdentity("Other", ClientType.LICENSED, UUID_TWO)));
        var previous = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.publish(List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE),
                new WhitelistIdentity("Player", ClientType.LICENSED, UUID_ONE))));
        assertSame(previous, registry.snapshot());
    }

    @Test void invalidPublicationCannotMakeInitialRegistryReady() {
        assertThrows(IllegalArgumentException.class, () -> registry.publish(List.of(
                new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE),
                new WhitelistIdentity("Player", ClientType.LICENSED, UUID_ONE))));
        assertFalse(registry.snapshot().ready());
        assertTrue(registry.snapshot().clientTypes().isEmpty());
    }

    @Test void nullMetadataRejectsEntirePublication() {
        registry.publish(List.of(new WhitelistIdentity("Player", ClientType.CRACKED, UUID_ONE)));
        var previous = registry.snapshot();
        assertThrows(NullPointerException.class, () -> registry.publish(List.of(
                new WhitelistIdentity("Other", null, UUID_TWO))));
        assertThrows(NullPointerException.class, () -> registry.publish(List.of(
                new WhitelistIdentity("Other", ClientType.LICENSED, null))));
        assertSame(previous, registry.snapshot());
    }
}
