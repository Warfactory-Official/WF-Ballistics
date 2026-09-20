package com.wf.wflib.warhead;

import com.wf.wflib.WFLib;
import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.standard.BlockAllocatorStandard;
import com.wf.wflib.aef.standard.BlockAllocatorWater;
import com.wf.wflib.aef.standard.BlockProcessorStandard;
import com.wf.wflib.aef.standard.EntityProcessorCross;
import com.wf.wflib.aef.standard.PlayerProcessorStandard;
import com.wf.wflib.fx.ExplosionCreator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A torpedo's warhead: an underwater blast, which is a different weapon from the same charge in air. */
public final class TorpedoWarhead {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "torpedo");

    /** Blast size against entities: what the shock does through water. */
    private static final float BLAST_SIZE = 9F;
    /** Fraction of that the terrain pass runs at: what it does to a seabed, which is much less. */
    private static final float TERRAIN_FRACTION = 0.45F;
    private static final int BLAST_RESOLUTION = 24;
    /** Entity reach multiplier. Water carries a shock a long way further than air does. */
    private static final float REACH_MOD = 2.2F;

    private TorpedoWarhead() {
    }

    public static void detonate(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        boolean wet = !level.getFluidState(BlockPos.containing(pos)).isEmpty();

        var xnt = new ExplosionAEF(level, pos.x, pos.y, pos.z, BLAST_SIZE * TERRAIN_FRACTION);
        xnt.setBlockAllocator(wet ? new BlockAllocatorWater(BLAST_RESOLUTION)
                : new BlockAllocatorStandard(BLAST_RESOLUTION));
        xnt.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        xnt.setEntityProcessor(new EntityProcessorCross()
                .withRangeMod(wet ? REACH_MOD : 1F));
        xnt.setPlayerProcessor(new PlayerProcessorStandard());
        xnt.igniterFaction(source.igniterFactionId());
        xnt.explode();
        ExplosionCreator.composeEffectStandard(level, pos.x, pos.y, pos.z);
    }
}
