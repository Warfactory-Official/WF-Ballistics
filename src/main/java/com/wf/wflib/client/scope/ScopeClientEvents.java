package com.wf.wflib.client.scope;

import com.wf.wflib.WFLib;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Keeps {@link ScopeCache} honest about its GPU textures. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ScopeClientEvents {

    private ScopeClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ScopeCache.tick();
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ScopeCache.clear();
    }
}
