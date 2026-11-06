package com.ticketrush.common.id;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RFC 9562 version-7 UUIDs: 48-bit Unix-ms timestamp, then a 12-bit counter (monotonic within a JVM),
 * then 62 random bits. Time-ordered, so InnoDB primary-key inserts stay sequential.
 * Uses a ReentrantLock rather than synchronized to avoid pinning virtual threads on Java 21.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static long lastMillis = -1;
    private static int counter;

    private UuidV7() {
    }

    public static UUID next() {
        long millis;
        int seq;
        LOCK.lock();
        try {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                counter = 0;
            } else if (++counter > 0xFFF) {      // counter exhausted within one ms: borrow the next ms
                lastMillis++;
                counter = 0;
            }
            millis = lastMillis;
            seq = counter;
        } finally {
            LOCK.unlock();
        }
        long msb = (millis << 16) | (0x7L << 12) | seq;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
