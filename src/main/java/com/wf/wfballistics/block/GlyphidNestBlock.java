package com.wf.wfballistics.block;

import net.minecraft.world.level.block.Block;

/**
 * The flesh a nest is built out of. Inert, and its own class rather than a bare {@link Block} only so
 * {@code EntityGlyphid.isSpawnerBlock} can name it: a colony will not chew through its own mound.
 */
public class GlyphidNestBlock extends Block {

    public GlyphidNestBlock(Properties props) {
        super(props);
    }
}
