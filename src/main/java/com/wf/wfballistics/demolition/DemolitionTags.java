package com.wf.wfballistics.demolition;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** What a mining charge is allowed to break, and what counts as a charge. */
public final class DemolitionTags {

    /** Stone, dirt, gravel, sand and ores: what a tier-1 charge chews through. */
    public static final TagKey<Block> NATURAL_BLAST_BREAKABLE = BlockTags.create(id("natural_blast_breakable"));

    /** The deepslate/tuff matrix. Tier 2 and up break these, on top of everything above. */
    public static final TagKey<Block> DEEP_BLAST_BREAKABLE = BlockTags.create(id("deep_blast_breakable"));

    /** Deepslate ores, kept apart from the broad ore tag so a tier-1 charge cannot cheat its way down. */
    public static final TagKey<Block> DEEP_ORES = BlockTags.create(id("deep_ores"));

    /** A block a detonator recognises. */
    public static final TagKey<Block> EXPLOSIVE = BlockTags.create(id("explosive"));

    private DemolitionTags() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, path);
    }
}
