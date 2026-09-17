package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.entity.FireLingeringEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class FireLightHandler {

    private FireLightHandler() {
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        if (event.getEntity() instanceof FireLingeringEntity fire) {
            WFDynamicLight.remove(fire.getId());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            WFDynamicLight.clear();
        }
    }
}
