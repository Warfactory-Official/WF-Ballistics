package com.wf.wfballistics.warhead;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.entity.mist.GasCloud;
import com.wf.wfballistics.fluid.WFFluids;
import com.wf.wfballistics.fx.ExplosionCreator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

/**
 * Chemical warhead: instead of a blast, it disperses a wall-respecting cloud of gas over the target area
 * (see {@link GasCloud}). A small burst FX marks the point of release; the lethality is the lingering cloud.
 *
 * <p>Best paired with an airburst fuze (a positive {@code explosionOffset}) so the agent is released above
 * the target and floods down and outward through the reachable space.
 */
public final class GasWarhead {

    /**
     * Registered warhead id (also selectable from the dispenser GUI).
     */
    public static final String ID = "gas";

    private GasWarhead() {
    }

    /**
     * The agent released. Mustard gas: a persistent, blistering, area-denial cloud.
     */
    private static Fluid agent() {
        return WFFluids.MUSTARD_GAS.get();
    }

    /**
     * Warhead entry point (registered as {@link #ID}).
     */
    public static void detonate(MissileEntity missile, Vec3 pos) {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        GasCloud.spawn(level, agent(), pos);
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    }
}
