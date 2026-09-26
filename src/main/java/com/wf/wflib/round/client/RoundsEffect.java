package com.wf.wflib.round.client;

import com.wf.wflib.client.flywheel.WFFlywheelEffect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelAccessor;

/** One per client level: draws every modelled round. */
final class RoundsEffect implements WFFlywheelEffect {

    private final ClientLevel level;

    RoundsEffect(ClientLevel level) {
        this.level = level;
    }

    @Override
    public LevelAccessor level() {
        return this.level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new RoundsVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
    }

    @Override
    public boolean isExpired() {
        return !ClientRounds.alive(this.level);
    }
}
