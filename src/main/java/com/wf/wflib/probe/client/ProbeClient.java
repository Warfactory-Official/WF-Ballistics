package com.wf.wflib.probe.client;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** The probe's client footprint: the panel and job layers above the crosshair, and the built-in providers. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ProbeClient {

    public static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "probe");
    public static final ResourceLocation JOB_LAYER =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "probe_job");

    private ProbeClient() {
    }

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, new ProbeOverlay());
        event.registerAbove(LAYER, JOB_LAYER, new ProbeJobOverlay());
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ProbeDefaults.register();
        });
    }
}
