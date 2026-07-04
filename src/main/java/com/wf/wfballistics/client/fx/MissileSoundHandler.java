package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MissileSoundHandler {

    private MissileSoundHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof MissileEntity missile)) return;

        Minecraft.getInstance().getSoundManager().play(new MissileFlightSound(missile));
    }
}
