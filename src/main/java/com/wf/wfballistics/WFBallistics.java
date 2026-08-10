package com.wf.wfballistics;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(WFBallistics.MODID)
public class WFBallistics {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "wfballistics";

    // FML injects IEventBus (the mod event bus) and ModContainer into the mod constructor.
    public WFBallistics(IEventBus modEventBus, ModContainer modContainer) {
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
        // Per-entity fire state is now a NeoForge data attachment (see fire/WFFire); register its type.
        com.wf.wfballistics.fire.WFFire.register(modEventBus);

        // Config: explicit listener (replaces modEventBus.register(WFConfig.class)) + registerConfig on the container.
        modEventBus.addListener(com.wf.wfballistics.config.WFConfig::onLoad);
        modContainer.registerConfig(
                ModConfig.Type.COMMON, com.wf.wfballistics.config.WFConfig.SPEC);
        modContainer.registerConfig(
                ModConfig.Type.CLIENT, com.wf.wfballistics.config.WFClientConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Networking now registers via RegisterPayloadHandlersEvent (see network/WFPayloadRegistrar);
            // no explicit call here.
            com.wf.wfballistics.entity.mist.MistEffects.bootstrap();
            // TODO(port): forced-chunk validation. Forge's ForgeChunkManager.setForcedChunkLoadingCallback
            //   is replaced by a TicketController registered from RegisterTicketControllersEvent
            //   (net.neoforged.neoforge.common.world.chunk). Handled in the chunk package during porting.
        });
    }
}
