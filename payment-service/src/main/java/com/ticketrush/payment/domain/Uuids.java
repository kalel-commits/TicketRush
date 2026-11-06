package com.ticketrush.payment.domain;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID <-> BINARY(16), same byte order as MySQL UUID_TO_BIN(uuid). */
public final class Uuids {

    private Uuids() {
    }

    public static byte[] toBytes(UUID id) {
        return id == null ? null : ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    public static UUID fromBytes(byte[] b) {
        if (b == null) return null;
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
