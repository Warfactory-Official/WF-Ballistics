package com.wf.wfballistics.warhead;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.entity.BombletEntity;
import com.wf.wfballistics.fx.ExplosionCreator;
import com.wf.wfballistics.util.FragmentationUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class FireCluster {

    public static final ResourceLocation ID = new ResourceLocation(WFBallistics.MODID, "fire_cluster");
    private static final double HALF_ANGLE = Math.toRadians(60.0);
    private static final double SPEED = 1.2;
    private static final double SPEED_JITTER = 0.4;
    public static boolean asFireballs = false;

    private FireCluster() {
    }

    public static void detonate(MissileEntity missile, Vec3 pos) {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        int count = missile.getFragmentCount();
        if (asFireballs) {
            spawnFireballs(level, pos, count);
        } else {
            FragmentationUtil.cone(level, pos, new Vec3(0.0, -1.0, 0.0), HALF_ANGLE, count,
                    SPEED, SPEED_JITTER, BombletEntity.FIRE_ID, BombletEntity.FIRE, BombletEntity.DEFAULT_FUSE, null);
        }
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    }

    private static void spawnFireballs(Level level, Vec3 pos, int count) {
        RandomSource rng = level.random;
        Vec3 axis = new Vec3(0.0, -1.0, 0.0);
        for (int i = 0; i < count; i++) {
            Vec3 dir = FragmentationUtil.randomConeVector(rng, axis, HALF_ANGLE);
            SmallFireball fireball = new SmallFireball(level, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z);
            fireball.setPos(pos.x, pos.y, pos.z);
            level.addFreshEntity(fireball);
        }
    }
}
