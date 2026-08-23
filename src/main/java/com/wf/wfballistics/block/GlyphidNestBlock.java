package com.wf.wfballistics.block;

import net.minecraft.world.level.block.Block;

/**
 * The flesh a nest is built out of. Inert: it holds no state and does nothing but be in the way.
 *
 * <p>Its own class rather than a bare {@link Block} so {@code EntityGlyphid.isNestBlock} can name it. A
 * colony will not chew through its own mound, and at 0.5 hardness a single dig would otherwise take a
 * chamber's worth of it out.
 */
public class GlyphidNestBlock extends Block {

    public GlyphidNestBlock(Properties props) {
        super(props);
    }
}
