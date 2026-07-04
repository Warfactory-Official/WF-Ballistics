package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.client.flywheel.FlywheelEffectManager;
import com.wf.wfballistics.entity.FireLingeringEntity;
import com.wf.wfballistics.fire.FireType;
import com.wf.wfballistics.fire.FlameCreator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class FireClientFX {

    private FireClientFX() {
    }

    public static void tick(FireLingeringEntity fire) {
        if (FlywheelEffectManager.isAvailable(fire.level())) {
            return;
        }
        spawnVanillaParticles(fire);
    }

    private static void spawnVanillaParticles(FireLingeringEntity fire) {
        Level level = fire.level();
        AABB box = fire.getBoundingBox();
        FireType type = fire.getVariant() == FireLingeringEntity.TYPE_PHOSPHORUS ? FireType.PHOSPHORUS : FireType.NORMAL;
        double dx = box.maxX - box.minX;
        double dz = box.maxZ - box.minZ;
        int count = Mth.clamp((int) (dx * dz * 0.75), 2, 24);
        for (int i = 0; i < count; i++) {
            double px = box.minX + level.random.nextDouble() * dx;
            double pz = box.minZ + level.random.nextDouble() * dz;
            Vec3 up = new Vec3(px, box.maxY, pz);
            Vec3 down = new Vec3(px, box.minY - 1.0, pz);
            BlockHitResult hit = level.clip(new ClipContext(up, down,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, fire));
            double gy = hit.getType() != HitResult.Type.MISS ? hit.getLocation().y : box.minY;
            FlameCreator.composeClient(level, px, gy, pz, type);
        }
    }
}
