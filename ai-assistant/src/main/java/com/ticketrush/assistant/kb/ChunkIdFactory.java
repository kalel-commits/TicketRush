package com.ticketrush.assistant.kb;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Deterministic point ids (spec §24.3): the same chunk always maps to the same Qdrant point, so re-ingesting upserts. */
public final class ChunkIdFactory {

    private ChunkIdFactory() {
    }

    public static UUID id(String source, String section, int chunkIndex) {
        return UUID.nameUUIDFromBytes((source + '\u0000' + section + '\u0000' + chunkIndex).getBytes(StandardCharsets.UTF_8));
    }
}
