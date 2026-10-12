package com.wf.wflib.build;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;

public interface BlueprintFormat {

    String id();

    /** No dot, lowercase. */
    String extension();

    /**
     * @param root decompressed, already within quota: see {@code BlueprintLibrary#read}.
     * @param blocks block-id resolver, passed in so this needs no level.
     * @param name fallback if the file carries none of its own.
     * @throws BlueprintException wrong format, malformed, or over {@link Blueprint#MAX_VOLUME}.
     */
    Blueprint read(CompoundTag root, HolderGetter<Block> blocks, String name) throws BlueprintException;

    /** Message is user-facing. */
    class BlueprintException extends Exception {
        public BlueprintException(String message) {
            super(message);
        }

        public BlueprintException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
