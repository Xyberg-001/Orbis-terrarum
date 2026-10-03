package com.berg.orbis.worldgen;

import java.nio.ByteBuffer;

/** A chunk's save data with its region-file bytes already compressed (see worldgen/ChunkCompression). */
public interface PrecompressedTag {
    ByteBuffer orbis$precompressed();

    void orbis$setPrecompressed(ByteBuffer bytes);
}
