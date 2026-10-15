package com.wf.wflib.warhead;

import com.wf.wflib.WFLib;
import com.wf.wflib.entity.AgentOrangeEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Defoliant: releases an {@link AgentOrangeEntity} spray; blast size = footprint radius, blocks. */
public final class AgentOrangeWarhead {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "agent_orange");
    /** Blocks the spray turns to air. */
    public static final TagKey<Block> TARGETS = BlockTags.create(ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
            "defoliant_targets"));
    public static final float RADIUS = 12.0f;

    private AgentOrangeWarhead() {
    }

    public static void detonate(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        float size = source.blastSize();
        AgentOrangeEntity.spawn(level, pos, Math.round(size > 0 ? size : RADIUS), source.igniterFactionId());
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 3.0f,
                0.5f);
    }
}
