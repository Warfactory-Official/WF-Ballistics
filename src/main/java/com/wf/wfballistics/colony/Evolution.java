package com.wf.wfballistics.colony;

import com.wf.wfballistics.industry.IndustryApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/**
 * How far the glyphids have come, as one number per level between 0 and 1.
 *
 * <p>Design §7 left the scope open: one scalar per level, or one per region. This is the per-level answer,
 * for the reason the design doc gave — it is the one a player can be told about and reason about. The cost
 * is that a quiet corner of the world does not stay quiet; a region-scoped version would fix that and is a
 * strictly larger change, since every colony would then need to know which region it is in.
 *
 * <p>Two inputs, and only one of them is really the point:
 * <ul>
 *   <li><b>Time</b> — a floor, so a world left alone still eventually hardens. Deliberately slow.</li>
 *   <li><b>Industry</b> — {@link IndustryApi#totalPressure}, the same signal that provokes individual
 *       colonies, summed across the world. This is the term that makes evolution a consequence of what was
 *       built.</li>
 * </ul>
 *
 * <p>Asymptotic rather than linear: each step closes a fixed share of the <em>remaining</em> gap, so
 * evolution never quite arrives and the last stretch costs far more industry than the first. That keeps the
 * top of the caste table something a world grows into rather than something it passes through in an evening.
 *
 * <p>What it buys the colonies is deliberately narrow — which castes they may field ({@link GlyphidCaste}),
 * and a modest bonus to how fast they grow and how many they send. It does not touch tier: distance decides
 * where the hard nests are, evolution decides what a hard nest has learned to build.
 *
 * <p>The factors are a starting point, not a measured balance. They are config so they can be moved without
 * a rebuild, and {@code /wfballistics colony evolution} sets the value outright for testing.
 */
public final class Evolution {

    /**
     * Ticks between recalculations. Nothing here is urgent and {@code totalPressure} walks the industry
     * index, so this runs at ten-second granularity rather than per tick.
     */
    public static final int INTERVAL = 200;

    private Evolution() {
    }

    public static float of(ServerLevel level) {
        return ColonyRegistry.get(level).evolution();
    }

    /**
     * Advance the scalar. Cheap and idempotent between intervals, so it is safe to call every colony tick.
     */
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

    /**
     * Multiplier applied to colony growth and warband size. One at a fresh world, and
     * {@code 1 + evolutionStrengthBonus} at full evolution.
     */
    public static double strength(float evolution) {
        return 1.0 + ColonyConfig.evolutionStrengthBonus() * Mth.clamp(evolution, 0.0F, 1.0F);
    }
}
