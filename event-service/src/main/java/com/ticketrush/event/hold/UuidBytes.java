package com.ticketrush.event.hold;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID <-> BINARY(16) in the same byte order as MySQL's UUID_TO_BIN(uuid) (no swap flag). */
public final class UuidBytes {

    private UuidBytes() {
    }

    public static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
