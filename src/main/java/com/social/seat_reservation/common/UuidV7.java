package com.social.seat_reservation.common;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class UuidV7 {

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    static UUID generate(long epochMilli) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        long most = (epochMilli & 0xFFFF_FFFF_FFFFL) << 16
                | 0x7000L
                | (random.nextLong() & 0x0FFFL);

        long least = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
                | 0x8000_0000_0000_0000L;

        return new UUID(most, least);
    }
}
