package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Gives every missile a Flywheel-instanced exhaust trail as it appears on the client. The example for "rocket
 * particles and similar repeating particles" — any flying entity can get a trail by spawning an
 * {@link InstancedTrailEffect} for it here (or wherever the entity is created client-side).
 */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MissileTrailHandler {

    private MissileTrailHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof MissileEntity missile)) return;
        if (!FlywheelEffectManager.isAvailable(event.getLevel())) return;

        FlywheelEffectManager.spawn(new InstancedTrailEffect(missile));
    }
}
