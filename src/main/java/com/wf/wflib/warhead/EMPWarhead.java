package com.wf.wflib.warhead;

import com.wf.wflib.WFLib;
import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.standard.BlockAllocatorBlockEntities;
import com.wf.wflib.aef.standard.BlockProcessorEMP;
import com.wf.wflib.fx.EMPCreator;
import net.minecraft.resources.ResourceLocation;
import com.wf.wflib.drone.cam.Datalink;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EMPWarhead {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "emp");

    private static final int RADIUS = 48;
    private static final int CHARGE_LOCK_SECONDS = 10;
    private static final boolean DRAIN_ENERGY = true;
    private static final boolean PAUSE_WORK = true;
    private static final boolean BREAK_MAINTENANCE = true;
    /** Datalinks of bodies in the radius: feeds, remote control. */
    public static final int LINK_KILL_TICKS = 300;

    private EMPWarhead() {
    }

    public static void detonate(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }

        ExplosionAEF emp = new ExplosionAEF(level, pos.x, pos.y, pos.z, RADIUS);
        emp.setBlockAllocator(new BlockAllocatorBlockEntities(RADIUS));
        emp.setBlockProcessor(new BlockProcessorEMP(CHARGE_LOCK_SECONDS, DRAIN_ENERGY, PAUSE_WORK, BREAK_MAINTENANCE));
        emp.bypassClaims(true);
        emp.explode();

        EMPCreator.compose(level, pos.x, pos.y, pos.z, RADIUS);
        for (Entity e : level.getEntities((Entity) null, new AABB(pos, pos).inflate(RADIUS),
                e -> !(e instanceof Player) && e.position().distanceToSqr(pos) <= RADIUS * RADIUS)) {
            Datalink.jam(e, LINK_KILL_TICKS);
        }
    }
}
