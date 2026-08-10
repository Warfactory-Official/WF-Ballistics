package com.wf.wfballistics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Optional GregTechCEu integration entry point.
 *
 * <p>TODO(port): the GregTech-facing implementation ({@code compat.gt.GregtechEMP} + {@code compat.gt.EMPLockable}
 * + {@code mixin.gt.MixinNotifiableEnergyContainer}) is temporarily excluded from compilation because GregTechCEu
 * for NeoForge 1.21.1 is not yet on the build classpath (its Maven coordinate is unconfirmed — see
 * porting-resources/00-PORTING-PLAYBOOK.md). This stub keeps the mod building and running without GregTech.
 * Re-enable by restoring the compile-only dependency in build.gradle, un-excluding {@code compat/gt/**} and
 * {@code mixin/gt/**}, re-adding {@code gt.MixinNotifiableEnergyContainer} to wfballistics.mixins.json, and
 * delegating {@link #applyEMP} back to {@code GregtechEMP.apply(...)}.
 */
public final class GregtechCompat {

    public static final String MODID = "gtceu";

    private static final boolean LOADED = ModList.get().isLoaded(MODID);

    private GregtechCompat() {
    }

    public static boolean isActive() {
        // Force-disabled until the GregTech 1.21.1 integration is re-enabled (see class javadoc).
        return false && LOADED;
    }

    public static boolean applyEMP(Level level, BlockPos pos, int lockTicks, boolean drain, boolean pauseWork,
                                   boolean breakMaintenance) {
        // TODO(port): return isActive() && GregtechEMP.apply(level, pos, lockTicks, drain, pauseWork, breakMaintenance);
        return false;
    }
}
