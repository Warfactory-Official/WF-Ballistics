package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.WFBallistics;
import dev.engine_room.flywheel.api.event.ReloadLevelRendererEvent;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the lifecycle of every {@link WFFlywheelEffect} (instanced smoke clouds, skeleton bone piles, ...).
 * Flywheel doesn't tick effects, so this adds them to the visualization manager, advances their simulation
 * each client tick, and removes them when spent.
 *
 * <p>{@link #isAvailable} lets callers fall back to vanilla rendering when the Flywheel backend is off
 * (e.g. the user selected the off/batched backend), since instanced effects only draw under it.
*/
@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class FlywheelEffectManager {

    private static final List<WFFlywheelEffect> ACTIVE = new ArrayList<>();


    private static boolean reregisterPending = false;

    private FlywheelEffectManager() { }

    /** @return whether Flywheel can render instances for this level right now */
    public static boolean isAvailable(Level level) {
        return VisualizationManager.supportsVisualization(level);
    }

    /** Adds an effect to Flywheel and starts ticking it. Assumes {@link #isAvailable} was checked. */
    public static void spawn(WFFlywheelEffect effect) {
        VisualizationHelper.queueAdd(effect);
        ACTIVE.add(effect);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.level == null) return;

        if (reregisterPending) {
            reregisterPending = false;
            for (WFFlywheelEffect effect : ACTIVE) {
                if (effect.level() == mc.level) {
                    VisualizationHelper.queueAdd(effect);
                }
            }
        }

        if (ACTIVE.isEmpty()) return;

        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            WFFlywheelEffect effect = ACTIVE.get(i);
            effect.tickEffect();
            if (effect.isExpired()) {
                VisualizationHelper.queueRemove(effect);
                ACTIVE.remove(i);
            }
        }
    }


    @SubscribeEvent
    public static void onRendererReload(ReloadLevelRendererEvent event) {
        if (!ACTIVE.isEmpty()) {
            reregisterPending = true;
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor unloaded = event.getLevel();
        if (!unloaded.isClientSide()) return;
        ACTIVE.removeIf(effect -> effect.level() == unloaded);
    }
}
