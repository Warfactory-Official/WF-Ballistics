package com.wf.wflib.colony;

import com.wf.wflib.industry.IndustryApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/** How far the glyphids have come, as one number per level between 0 and 1. */
public final class Evolution {

    /** Ticks between recalculations. {@code totalPressure} walks the industry index, so keep it coarse. */
    public static final int INTERVAL = 200;

    private Evolution() {
    }

    public static float of(ServerLevel level) {
        return ColonyRegistry.get(level).evolution();
    }

    /** Advance the scalar. Idempotent between intervals, so it is safe to call every colony tick. */
    public static void tick(ServerLevel level, ColonyRegistry registry) {
        if (level.getGameTime() % INTERVAL != 0L) {
            return;
        }

        float current = registry.evolution();
        if (current >= 1.0F) {
            return;
        }

        double factor = ColonyConfig.evolutionTimeFactor()
                + ColonyConfig.evolutionPressureFactor() * IndustryApi.totalPressure(level);
        if (factor <= 0.0) {
            return;
        }

        registry.setEvolution((float) (current + (1.0 - current) * Math.min(1.0, factor)));
    }

    /** Multiplier applied to colony growth and warband size. */
    public static double strength(float evolution) {
        return 1.0 + ColonyConfig.evolutionStrengthBonus() * Mth.clamp(evolution, 0.0F, 1.0F);
    }
}
