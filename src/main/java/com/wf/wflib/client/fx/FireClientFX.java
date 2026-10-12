package com.wf.wflib.client.fx;

import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.client.flywheel.InstancedFlameEffect;
import com.wf.wflib.entity.FireLingeringEntity;
import com.wf.wflib.fire.FireType;
import com.wf.wflib.fire.FlameCreator;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class FireClientFX {

    private static final int FIRE_LIGHT = 15;

    private FireClientFX() {
    }

    public static void tick(FireLingeringEntity fire) {
        registerLight(fire);
        if (FlywheelEffectManager.isAvailable(fire.level())) {
            return;
        }
        spawnVanillaParticles(fire);
    }

    private static void registerLight(FireLingeringEntity fire) {
        if (!WFLights.fire(fire)) {
            WFDynamicLight.add(fire.getId(), WFLights.lightPos(fire), FIRE_LIGHT);
        }
    }

    private static void spawnVanillaParticles(FireLingeringEntity fire) {
        Level level = fire.level();
        AABB box = fire.getBoundingBox();
        FireType type = fire.getFireType();
        double dx = box.maxX - box.minX;
        double dz = box.maxZ - box.minZ;
        int count = InstancedFlameEffect.flameCount(dx, dz);
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
