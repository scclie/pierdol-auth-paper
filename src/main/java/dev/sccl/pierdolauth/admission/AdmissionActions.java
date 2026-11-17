package dev.sccl.pierdolauth.admission;

import java.util.UUID;

public interface AdmissionActions {
    void prompt(UUID uuid);
    void message(UUID uuid, String message);
    void release(UUID uuid);
    void kick(UUID uuid, String reason);
}
