package dev.sccl.pierdolauth.admission;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class ConnectionIdentity {
    private final UUID uuid;
    private final String nickname;
    private final byte[] address;

    public ConnectionIdentity(UUID uuid, String nickname, InetAddress address) {
        this.uuid = Objects.requireNonNull(uuid);
        this.nickname = Objects.requireNonNull(nickname);
        this.address = Objects.requireNonNull(address).getAddress().clone();
    }

    public UUID uuid() { return uuid; }
    public String nickname() { return nickname; }
    public byte[] address() { return address.clone(); }
    public boolean sameAddress(ConnectionIdentity other) {
        return Arrays.equals(address, other.address);
    }
}
