package com.wf.wflib.client.rail;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.journeymap.rail.RailMapEditor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Drives the editor's autosave, and drops the routes when the connection that produced them goes away. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class RailClientEvents {

    private RailClientEvents() {
    }

    /** The working route is saved from here, so closing the map does not strand the last edit. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        RailMapEditor.tickClient();
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Otherwise one server's railways stay drawn over the next world's terrain. The working copy
        // is dropped too: it is a view of a route on that server, and it means nothing on the next one.
        PublishedAlignments.clear();
        RouteOwnership.clear();
        RailMapEditor.resetSession();
    }
}
