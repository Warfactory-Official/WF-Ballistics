package com.wf.wflib.build;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;

/** One way of getting a {@link Blueprint} off disk. */
public interface BlueprintFormat {

    String id();

    /**
     * @return the file extension this reads, without the dot, lowercase.
     */
    String extension();

    /**
     * Turn the file's root tag into a blueprint.
     *
     * @param root the root compound, already decompressed and already within the read quota: see
     *      {@code BlueprintLibrary#read}
     * @param blocks how to resolve a block id. Passed in rather than reached for so this stays callable
     *      without a level
     * @param name what to call the result if the file itself does not say
     * @throws BlueprintException if the file is not this format, is malformed, or is bigger than
     *      {@link Blueprint#MAX_VOLUME}
     */
    Blueprint read(CompoundTag root, HolderGetter<Block> blocks, String name) throws BlueprintException;

    /**
     * A file that could not be read, with a reason fit to put in front of whoever tried to load it.
     */
    class BlueprintException extends Exception {
        public BlueprintException(String message) {
            super(message);
        }

        public BlueprintException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
