package dev.sccl.pierdolauth.licensed;

import static dev.sccl.pierdolauth.api.GameAuthModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.games647.fastlogin.core.storage.AuthStorage;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FastLoginProjectionTest {
    private static final UUID ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final WhitelistIdentity LICENSED = new WhitelistIdentity("LiCeNsEd", ClientType.LICENSED, ID);

    @Test void newProfileUsesEmptyNonNullLastIpAndPersistsIndependently() {
        var storage = new FakeStorage();
        assertTrue(apply(storage));
        var profile = storage.loadProfile(ID);
        assertNotNull(profile, "new profile must actually persist, not just return from save");
        assertEquals("", profile.getLastIp());
        assertDesired(profile);
        assertEquals(profile.getRowId(), storage.loadProfile("LiCeNsEd").getRowId());
        profile.setPremium(false);
        assertTrue(storage.loadProfile(ID).isPremium(), "loads must not alias persisted state");
    }

    @Test void crackedSnapshotMakesNoStorageCalls() {
        var storage = new FakeStorage();
        assertTrue(new FastLoginProjection(storage, () -> false).apply(snapshot(
                new WhitelistIdentity("Cracked", ClientType.CRACKED, OTHER))));
        assertEquals(0, storage.calls);
    }

    @Test void mainThreadRefusesWithoutStorageCalls() {
        var storage = new FakeStorage();
        assertFalse(new FastLoginProjection(storage, () -> true).apply(snapshot(LICENSED)));
        assertEquals(0, storage.calls);
    }

    @Test void existingRowUpdatesWithoutChangingPersistentIdentity() {
        var storage = new FakeStorage();
        long row = storage.seed(ID, "LiCeNsEd", false);
        assertTrue(apply(storage));
        assertDesired(storage.rows.get(row));
        assertEquals(1, storage.rows.size());
    }

    @Test void historicalNullUuidNameRowIsAdoptedWithoutInsert() {
        var storage = new FakeStorage();
        long row = storage.seed(null, "LiCeNsEd", false);
        assertTrue(apply(storage));
        assertDesired(storage.rows.get(row));
        assertEquals(1, storage.rows.size());
    }

    @Test void uuidRowRenamesToFreeExactNamePreservingRowId() {
        var storage = new FakeStorage();
        long row = storage.seed(ID, "OldName", false);
        assertTrue(apply(storage));
        assertDesired(storage.rows.get(row));
        assertFalse(storage.loadProfile("OldName").isExistingPlayer());
        assertEquals(row, storage.loadProfile("LiCeNsEd").getRowId());
        assertEquals(1, storage.rows.size());
    }

    @Test void matchingStateIsIdempotentWithoutRequiringWriteAcknowledgement() {
        var storage = new FakeStorage();
        storage.seed(ID, "LiCeNsEd", true);
        storage.throwSave = true;
        assertTrue(apply(storage));
        assertEquals(0, storage.saves);
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert", "update", "zeroRows", "throw", "nameFailure", "uuidFailure"})
    void unconfirmedPersistenceFails(String failure) {
        var storage = new FakeStorage();
        if (!failure.equals("insert")) storage.seed(ID, "LiCeNsEd", false);
        switch (failure) {
            case "insert", "update" -> storage.dropSave = true;
            case "zeroRows" -> storage.beforeSave = storage.rows::clear;
            case "throw" -> storage.throwSave = true;
            case "nameFailure" -> storage.failName = true;
            case "uuidFailure" -> storage.failUuid = true;
            default -> fail("unknown case");
        }
        assertFalse(apply(storage), failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nameNull", "nameFresh", "uuidNull", "uuidFresh", "wrongUuid",
            "wrongName", "wrongCase", "preference", "rowId", "lastIp", "readThrow"})
    void independentReadbackMustMatchBothKeysAndPersistentRow(String failure) {
        var storage = new FakeStorage();
        storage.afterSave = () -> {
            switch (failure) {
                case "nameNull" -> storage.failName = true;
                case "nameFresh" -> storage.nameRead = p -> new StoredProfile(null, "LiCeNsEd", false, "");
                case "uuidNull" -> storage.failUuid = true;
                case "uuidFresh" -> storage.uuidRead = p -> new StoredProfile(ID, "LiCeNsEd", true, "");
                case "wrongUuid" -> storage.nameRead = p -> { p.setId(OTHER); return p; };
                case "wrongName" -> storage.uuidRead = p -> { p.setPlayerName("Other"); return p; };
                case "wrongCase" -> storage.nameRead = p -> { p.setPlayerName("licensed"); return p; };
                case "preference" -> storage.uuidRead = p -> { p.setOnlinemodePreferred(false); return p; };
                case "rowId" -> storage.uuidRead = p -> { p.setRowId(999); return p; };
                case "lastIp" -> storage.nameRead = p -> { p.setLastIp(null); return p; };
                case "readThrow" -> storage.nameRead = p -> { throw new IllegalStateException("synthetic"); };
                default -> fail("unknown case");
            }
        };
        assertFalse(apply(storage), failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"twoRows", "nullOwnerTwoRows", "differentOwner", "duplicateUuid"})
    void conflictsFailWithoutRebindingOrDeletingRows(String conflict) {
        var storage = new FakeStorage();
        if (!conflict.equals("differentOwner")) storage.seed(ID, "OldName", true);
        UUID owner = switch (conflict) {
            case "nullOwnerTwoRows" -> null;
            case "duplicateUuid" -> ID;
            default -> OTHER;
        };
        long target = storage.seed(owner, "LiCeNsEd", false);
        assertFalse(apply(storage));
        assertEquals(0, storage.saves);
        assertEquals(owner, storage.rows.get(target).getId());
        assertFalse(storage.rows.get(target).isPremium());
        assertEquals(conflict.equals("differentOwner") ? 1 : 2, storage.rows.size());
    }

    @Test void laterIdentityFailureBlocksWholeSnapshot() {
        var storage = new FakeStorage();
        storage.afterSave = () -> storage.dropSave = true;
        assertFalse(new FastLoginProjection(storage, () -> false).apply(snapshot(LICENSED,
                new WhitelistIdentity("Second", ClientType.LICENSED, OTHER))));
        assertEquals(1, storage.rows.size(), "partial cache writes are not rolled back");
    }

    @Test void finalRecheckDetectsLaterInvalidationOfEarlierConfirmedIdentity() {
        var storage = new FakeStorage();
        storage.afterSave = () -> {
            if (storage.saves == 2) storage.rows.values().iterator().next().setPremium(false);
        };
        assertFalse(new FastLoginProjection(storage, () -> false).apply(snapshot(LICENSED,
                new WhitelistIdentity("Second", ClientType.LICENSED, OTHER))));
        assertEquals(2, storage.rows.size(), "both inserts persisted before final recheck");
    }

    @Test void swappingCurrentNamesFailsWithoutChangingEitherRow() {
        var storage = new FakeStorage();
        long first = storage.seed(ID, "Second", true);
        long second = storage.seed(OTHER, "LiCeNsEd", true);
        assertFalse(new FastLoginProjection(storage, () -> false).apply(snapshot(LICENSED,
                new WhitelistIdentity("Second", ClientType.LICENSED, OTHER))));
        assertEquals(0, storage.saves);
        assertEquals(ID, storage.rows.get(first).getId());
        assertEquals(OTHER, storage.rows.get(second).getId());
    }

    @Test void caseDistinctHistoricalNameIsNotAnInferredCaseInsensitiveConflict() {
        var storage = new FakeStorage();
        long historical = storage.seed(OTHER, "licensed", false);
        long renamed = storage.seed(ID, "OldName", false);
        assertTrue(apply(storage));
        assertDesired(storage.rows.get(renamed));
        assertEquals(OTHER, storage.rows.get(historical).getId());
        assertEquals("licensed", storage.rows.get(historical).getName());
    }

    @Test void hiddenDuplicateUuidCannotBeExcludedByLimitOneTypedLookups() {
        var storage = new FakeStorage();
        storage.seed(ID, "LiCeNsEd", true);
        storage.seed(ID, "HiddenOldName", false);
        assertTrue(apply(storage), "observed matching row cannot prove global UUID uniqueness");
        assertEquals(2, storage.rows.size());
        assertEquals(0, storage.saves);
    }

    private static boolean apply(FakeStorage storage) {
        return new FastLoginProjection(storage, () -> false).apply(snapshot(LICENSED));
    }

    private static void assertDesired(StoredProfile profile) {
        assertTrue(profile.isExistingPlayer());
        assertEquals(ID, profile.getId());
        assertEquals("LiCeNsEd", profile.getName());
        assertTrue(profile.isOnlinemodePreferred());
        assertTrue(profile.isPremium());
        assertNotNull(profile.getLastIp());
    }

    private static WhitelistRegistry.Snapshot snapshot(WhitelistIdentity... identities) {
        var registry = new WhitelistRegistry();
        registry.publish(List.of(identities));
        return registry.snapshot();
    }

    // pinned schema: unique names, nullable duplicate uuids, rowId updates, independent copies and silent persistence failures.
    private static final class FakeStorage implements AuthStorage {
        private final Map<Long, StoredProfile> rows = new LinkedHashMap<>();
        private long nextRow;
        private int calls;
        private int saves;
        private boolean dropSave;
        private boolean throwSave;
        private boolean failName;
        private boolean failUuid;
        private Runnable beforeSave = () -> {};
        private Runnable afterSave = () -> {};
        private UnaryOperator<StoredProfile> nameRead = profile -> profile;
        private UnaryOperator<StoredProfile> uuidRead = profile -> profile;

        private static StoredProfile copy(StoredProfile profile) {
            if (profile == null) return null;
            return new StoredProfile(profile.getRowId(), profile.getId(), profile.getName(),
                    profile.isPremium(), profile.getFloodgate(), profile.getLastIp(), profile.getLastLogin());
        }

        private long seed(UUID uuid, String name, boolean premium) {
            var profile = new StoredProfile(uuid, name, premium, "");
            profile.setRowId(nextRow++);
            rows.put(profile.getRowId(), copy(profile));
            return profile.getRowId();
        }

        @Override public StoredProfile loadProfile(String name) {
            calls++;
            if (failName) return null;
            var profile = rows.values().stream().filter(row -> row.getName().equals(name))
                    .findFirst().map(FakeStorage::copy)
                    .orElseGet(() -> new StoredProfile(null, name, false, ""));
            return nameRead.apply(profile);
        }

        @Override public StoredProfile loadProfile(UUID uuid) {
            calls++;
            if (failUuid) return null;
            return uuidRead.apply(rows.values().stream().filter(row -> Objects.equals(uuid, row.getId()))
                    .findFirst().map(FakeStorage::copy).orElse(null));
        }

        @Override public void save(StoredProfile profile) {
            calls++;
            saves++;
            if (throwSave) throw new IllegalStateException("synthetic failure");
            beforeSave.run();
            if (dropSave || profile.getLastIp() == null) return;
            if (rows.values().stream().anyMatch(row -> row.getName().equals(profile.getName())
                    && row.getRowId() != profile.getRowId())) return;
            if (!profile.isExistingPlayer()) profile.setRowId(nextRow++);
            else if (!rows.containsKey(profile.getRowId())) return;
            rows.put(profile.getRowId(), copy(profile));
            afterSave.run();
        }

        @Override public int deleteProfile(String name) { throw new AssertionError("no deletion"); }
        @Override public void close() { throw new AssertionError("shared storage is not owned here"); }
    }
}
