package com.wf.wflib.client.recon;

import com.wf.wflib.WFLib;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Drops the grid overlay when the connection that produced it goes away. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ReconMapClientEvents {

    private ReconMapClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ReconMapClient.clear();
    }
}
