package com.wf.wflib.client.render;

/**
 * Mixin-injected on {@link com.mojang.blaze3d.vertex.BufferBuilder}: native buffer address + cursor,
 * for direct Unsafe vertex writes, no scratch copy.
 */
public interface DirectBufferAccess {

    /** Refreshed on buffer growth; re-fetch after growing. */
    long hbm$bufferAddress();

    int hbm$nextElementByte();

    void hbm$setNextElementByte(int value);

    int hbm$vertices();

    void hbm$setVertices(int value);

    void hbm$ensureCapacity(int bytes);
}
