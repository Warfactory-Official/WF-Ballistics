package com.wf.wfballistics.mine;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/** What a mine recognises in your hand. */
public final class MineTags {

    /** Held to defuse a mine built {@link DefuseMethod#TOOL}. */
    public static final TagKey<Item> DEFUSER = ItemTags.create(id("defuser"));

    private MineTags() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, path);
    }
}
