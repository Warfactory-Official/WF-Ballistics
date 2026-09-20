package com.wf.wflib.door.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.door.ModDoors;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** The door feature's whole client-side footprint: one visualizer and one rig-declaration call. */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DoorClient {

    private DoorClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            DoorRigs.init();
            SimpleBlockEntityVisualizer.builder(ModDoors.DOOR.get())
                    .factory(DoorVisual::new)
                    // The visual draws the whole door, so nothing vanilla should draw it again.
                    .skipVanillaRender(door -> true)
                    .apply();
        });
    }
}
