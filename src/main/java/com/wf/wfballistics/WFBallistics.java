package com.wf.wfballistics;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(WFBallistics.MODID)
public class WFBallistics {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "wfballistics";

    public WFBallistics(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
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

        modEventBus.register(com.wf.wfballistics.config.WFConfig.class);
       ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, com.wf.wfballistics.config.WFConfig.SPEC);
        ModLoadingContext.get().registerConfig(
               ModConfig.Type.CLIENT, com.wf.wfballistics.config.WFClientConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            com.wf.wfballistics.network.WFNetwork.register();
            com.wf.wfballistics.entity.mist.MistEffects.bootstrap();
            net.minecraftforge.common.world.ForgeChunkManager.setForcedChunkLoadingCallback(
                    MODID, com.wf.wfballistics.chunk.WFChunkValidation::validate);
        });
    }
}
