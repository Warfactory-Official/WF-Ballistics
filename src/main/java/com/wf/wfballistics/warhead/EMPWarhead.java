package com.wf.wfballistics.warhead;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorBlockEntities;
import com.wf.wfballistics.aef.standard.BlockProcessorEMP;
import com.wf.wfballistics.fx.EMPCreator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class EMPWarhead {

    public static final ResourceLocation ID = new ResourceLocation(WFBallistics.MODID, "emp");

    private static final int RADIUS = 48;
    private static final int CHARGE_LOCK_SECONDS = 10;
    private static final boolean DRAIN_ENERGY = true;
    private static final boolean PAUSE_WORK = true;
    private static final boolean BREAK_MAINTENANCE = true;

    private EMPWarhead() {
    }

    public static void detonate(MissileEntity missile, Vec3 pos) {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }

        ExplosionAEF emp = new ExplosionAEF(level, pos.x, pos.y, pos.z, RADIUS);
        emp.setBlockAllocator(new BlockAllocatorBlockEntities(RADIUS));
        emp.setBlockProcessor(new BlockProcessorEMP(CHARGE_LOCK_SECONDS, DRAIN_ENERGY, PAUSE_WORK, BREAK_MAINTENANCE));
        emp.bypassClaims(true);
        emp.explode();

        EMPCreator.compose(level, pos.x, pos.y, pos.z, RADIUS);
    }
}
