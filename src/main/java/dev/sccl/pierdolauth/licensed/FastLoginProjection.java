package dev.sccl.pierdolauth.licensed;

import com.github.games647.fastlogin.core.storage.AuthStorage;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import dev.sccl.pierdolauth.api.GameAuthModels.ClientType;
import dev.sccl.pierdolauth.api.GameAuthModels.WhitelistIdentity;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import dev.sccl.pierdolauth.whitelist.WhitelistSynchronizer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

public final class FastLoginProjection implements WhitelistSynchronizer.OptionalProjection {
    private static final Logger LOGGER = Logger.getLogger(FastLoginProjection.class.getName());

    private final AuthStorage storage;
    private final BooleanSupplier isMainThread;
    private final Runnable invalidateIntegration;

    public FastLoginProjection(AuthStorage storage, BooleanSupplier isMainThread) {
        this(storage, isMainThread, () -> {});
    }

    public FastLoginProjection(AuthStorage storage, BooleanSupplier isMainThread, Runnable invalidateIntegration) {
        this.storage = Objects.requireNonNull(storage);
        this.isMainThread = Objects.requireNonNull(isMainThread);
        this.invalidateIntegration = Objects.requireNonNull(invalidateIntegration);
    }

    @Override public void disable() {
        invalidateIntegration.run();
        LOGGER.warning("FastLogin integration unavailable");
    }

    @Override public boolean apply(WhitelistRegistry.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (isMainThread.getAsBoolean()) {
            return failed();
        }
        try {
            for (WhitelistIdentity identity : snapshot.identities().values()) {
                if (identity.clientType() != ClientType.LICENSED) continue;
                StoredProfile named = storage.loadProfile(identity.nickname());
                StoredProfile byUuid = storage.loadProfile(identity.uuid());
                if (named == null || (byUuid != null && (!byUuid.isExistingPlayer()
                        || !identity.uuid().equals(byUuid.getId())))) return failed();

                StoredProfile profile;
                if (named.isExistingPlayer()) {
                    if (named.getId() != null && !identity.uuid().equals(named.getId())) return failed();
                    if (byUuid == null) {
                        // only an unbound historical name row can be safely adopted
                        if (named.getId() != null) return failed();
                    } else if (named.getRowId() != byUuid.getRowId()) {
                        return failed();
                    }
                    profile = named;
                } else if (byUuid != null) {
                    // rename the existing uuid row instead of creating a duplicate
                    profile = byUuid;
                } else {
                    profile = new StoredProfile(identity.uuid(), identity.nickname(), true, "");
                }
                long existingRow = profile.getRowId();
                if (!desired(profile, identity)) {
                    profile.setId(identity.uuid());
                    profile.setPlayerName(identity.nickname());
                    profile.setOnlinemodePreferred(true);
                    storage.save(profile);
                }
                if (!confirmed(identity, existingRow)) return failed();
            }
            // later cache writes must not knowingly invalidate an earlier entry.
            for (WhitelistIdentity identity : snapshot.identities().values()) {
                if (identity.clientType() == ClientType.LICENSED && !confirmed(identity, -1)) return failed();
            }
            return true;
        } catch (RuntimeException ignored) {
            return failed();
        }
    }

    // reload independently to verify saved state, not transaction success.
    // the void save api may silently ignore sql failures.
    private boolean confirmed(WhitelistIdentity identity, long existingRow) {
        StoredProfile named = storage.loadProfile(identity.nickname());
        StoredProfile byUuid = storage.loadProfile(identity.uuid());
        return desired(named, identity) && desired(byUuid, identity)
                && named.getRowId() == byUuid.getRowId()
                && (existingRow < 0 || named.getRowId() == existingRow);
    }

    private static boolean desired(StoredProfile profile, WhitelistIdentity identity) {
        return profile != null && profile.isExistingPlayer()
                && identity.uuid().equals(profile.getId())
                && identity.nickname().equals(profile.getName())
                && profile.isOnlinemodePreferred() && profile.getLastIp() != null;
    }

    private static boolean failed() {
        LOGGER.warning("FastLogin projection failed");
        return false;
    }
}
