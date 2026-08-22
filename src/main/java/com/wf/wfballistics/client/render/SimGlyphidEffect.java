package com.wf.wfballistics.client.render;

import com.wf.wfballistics.client.flywheel.FlywheelEffectManager;
import com.wf.wfballistics.client.flywheel.WFFlywheelEffect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * One flywheel effect for the whole sim tier, rather than one per glyphid.
 *
 * <p>The entity tier gets a visual each, because flywheel builds one per entity and that is the right shape
 * when the entities exist anyway. Records do not exist as anything, so making one effect per record would be
 * inventing the per-object bookkeeping the tier was written to delete — a lifecycle, a visualisation manager
 * entry and an instancer lookup each. One effect owns the lot and hands the whole set to
 * {@link SimGlyphidVisual} to pose in one pass.
 */
public final class SimGlyphidEffect implements WFFlywheelEffect {

    private static @Nullable SimGlyphidEffect live;

    private final Level level;
    private boolean expired;

    private SimGlyphidEffect(Level level) {
        this.level = level;
    }

    /**
     * Make sure this level has an effect drawing its records, creating one if the last was retired.
     */
    public static void ensureLive(Level level) {
        SimGlyphidEffect current = live;
        if (current != null && !current.expired && current.level == level) {
            return;
        }
        if (!FlywheelEffectManager.isAvailable(level)) {
            return;
        }
        SimGlyphidEffect effect = new SimGlyphidEffect(level);
        live = effect;
        FlywheelEffectManager.spawn(effect);
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new SimGlyphidVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        SimGlyphids.tick();
        if (SimGlyphids.idleTooLong()) {
            expired = true;
        }
    }

    @Override
    public boolean isExpired() {
        return expired;
    }
}
