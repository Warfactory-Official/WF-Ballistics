package com.wf.wfballistics.warhead;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.nuke.ExplosionNukeGeneric;
import com.wf.wfballistics.entity.FireLingeringEntity;
import com.wf.wfballistics.fx.ExplosionSmallCreator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class BombletWarhead {

    public static final ResourceLocation ID = new ResourceLocation(WFBallistics.MODID, "bomblet");
    public static final ResourceLocation FIRE_ID = new ResourceLocation(WFBallistics.MODID, "bomblet_fire");

    public static final float BLAST_RADIUS = 4.0f;
    public static final float MAX_DAMAGE = 40.0f;

    public static final WarheadRegistry.Detonation STANDARD = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionNukeGeneric.dealDamage(level, pos, BLAST_RADIUS, MAX_DAMAGE);
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 3, 1.0f, 0.6f);
    };

    public static final WarheadRegistry.Detonation FIRE = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionNukeGeneric.dealDamage(level, pos, BLAST_RADIUS, MAX_DAMAGE * 0.5f);
        FireLingeringEntity.spawn(level, pos.x, pos.y, pos.z, 3.0f, 2.0f, 120, FireLingeringEntity.TYPE_DIESEL);
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 3, 1.0f, 0.6f);
    };

    private BombletWarhead() {
    }
}
