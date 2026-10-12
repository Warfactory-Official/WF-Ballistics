package com.wf.wflib.client.render;

import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.client.flywheel.WFFlywheelEffect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;

/** One flywheel effect per sim tier, not per glyphid. */
public final class SimGlyphidEffect implements WFFlywheelEffect {

    private static @Nullable SimGlyphidEffect live;

    private final Level level;
    private boolean expired;

    private SimGlyphidEffect(Level level) {
        this.level = level;
    }

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
