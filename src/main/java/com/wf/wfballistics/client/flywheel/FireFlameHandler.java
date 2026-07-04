package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.entity.FireLingeringEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class FireFlameHandler {

    private FireFlameHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof FireLingeringEntity fire)) return;
        if (!FlywheelEffectManager.isAvailable(event.getLevel())) return;

        FlywheelEffectManager.spawn(new InstancedFlameEffect(fire));
    }
}
