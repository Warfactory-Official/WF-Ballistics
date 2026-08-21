package com.wf.wfballistics;

import com.wf.wfballistics.config.WFClientConfig;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.drone.DroneTracker;
import com.wf.wfballistics.drone.sim.SimDroneManager;
import com.wf.wfballistics.entity.mist.MistEffects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(WFBallistics.MODID)
public class WFBallistics {
    public static final String MODID = "wfballistics";

    public WFBallistics(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        ModEntities.register(modEventBus);
        com.wf.wfballistics.block.ModBlocks.register(modEventBus);
        com.wf.wfballistics.block.ModBlockEntities.register(modEventBus);
        com.wf.wfballistics.item.ModItems.register(modEventBus);
        com.wf.wfballistics.WFCreativeTabs.register(modEventBus);
        com.wf.wfballistics.menu.ModMenus.register(modEventBus);
        com.wf.wfballistics.client.particle.WFParticles.register(modEventBus);
        com.wf.wfballistics.fluid.WFFluids.register(modEventBus);
        com.wf.wfballistics.WFSounds.register(modEventBus);
        com.wf.wfballistics.fire.WFFire.register(modEventBus);

        modEventBus.addListener(WFConfig::onLoad);
        modContainer.registerConfig(
                ModConfig.Type.COMMON, WFConfig.SPEC);
        modContainer.registerConfig(
                ModConfig.Type.CLIENT, WFClientConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
           MistEffects.bootstrap();
           DroneTracker.bootstrap();
           SimDroneManager.bootstrap();
        });
    }
}
