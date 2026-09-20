package com.wf.wflib.compat;

import com.wf.wflib.compat.gt.GregtechEMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

public final class GregtechCompat {

    public static final String MODID = "gtceu";

    private static final boolean LOADED = ModList.get().isLoaded(MODID);

    private GregtechCompat() {
    }

    public static boolean isActive() {
        return LOADED;
    }

    public static boolean applyEMP(Level level, BlockPos pos, int lockTicks, boolean drain, boolean pauseWork,
                                   boolean breakMaintenance) {
        return isActive() && GregtechEMP.apply(level, pos, lockTicks, drain, pauseWork, breakMaintenance);
    }
}
