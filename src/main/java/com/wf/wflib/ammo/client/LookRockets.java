package com.wf.wflib.ammo.client;

import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.ProjectileLook;
import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.client.flywheel.InstancedTrailEffect;
import com.wf.wflib.round.RocketEntity;
import com.wf.wflib.round.client.RoundRenderers;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Client rockets whose preset is a variant with a visible look and no {@link RoundRenderers} entry; trail while
 * the motor burns.
 */
public final class LookRockets {
    private static final Set<RocketEntity> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    private LookRockets() {
    }

    /** {@code RocketClient.tick}, first tick. */
    public static void launched(RocketEntity rocket) {
        ProjectileLook look = rocket.preset() == null ? null : Ammo.lookOrNull(rocket.preset().id());
        if (look == null || !look.visible() || RoundRenderers.get(rocket.preset().id()) != null) {
            return;
        }
        LIVE.add(rocket);
        if (look.trail() && FlywheelEffectManager.isAvailable(rocket.level())) {
            FlywheelEffectManager.spawn(new InstancedTrailEffect(rocket.level(), new InstancedTrailEffect.Source() {
                @Override
                public boolean gone() {
                    return rocket.isRemoved();
                }

                @Override
                public Vec3 emitPoint() {
                    return new Vec3(rocket.xOld, rocket.yOld, rocket.zOld);
                }

                @Override
                public boolean laying() {
                    return rocket.burning();
                }
            }));
        }
    }

    public static Set<RocketEntity> live() {
        LIVE.removeIf(RocketEntity::isRemoved);
        return LIVE;
    }
}
