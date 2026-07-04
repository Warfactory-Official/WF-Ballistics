package com.wf.wfballistics.warhead;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockMutatorFire;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.aef.standard.CustomDamageHandlerAmat;
import com.wf.wfballistics.aef.standard.EntityProcessorCross;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.fire.FireUtil;
import com.wf.wfballistics.fx.ExplosionCreator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class FireWarhead {

    public static final String ID = "fire";

    private static final float BLAST_RADIUS = 8F;
    private static final int BLAST_RESOLUTION = 24;
    private static final float BURN_TICKS = 200F;

    private FireWarhead() {
    }

    public static void detonate(MissileEntity missile, Vec3 pos) {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        var xnt = new ExplosionAEF(level, pos.x, pos.y, pos.z, BLAST_RADIUS);
        xnt.setBlockAllocator(new BlockAllocatorStandard(BLAST_RESOLUTION));
        xnt.setBlockProcessor(new BlockProcessorStandard().setNoDrop().withBlockEffect(new BlockMutatorFire()));
        xnt.setEntityProcessor(new EntityProcessorCross()
                .withRangeMod(2F)
                .withDamageMod(new CustomDamageHandlerAmat(BURN_TICKS)));
        xnt.setPlayerProcessor(new PlayerProcessorStandard());
        xnt.explode();
        ExplosionCreator.composeEffectStandard(level, pos.x, pos.y, pos.z);
        FireUtil.spawn(level, pos, 10, 1.2f, 15*20*20, 0);
    }
}
