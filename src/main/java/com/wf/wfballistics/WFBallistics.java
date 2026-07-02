package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(WFBallistics.MODID)
public class WFBallistics
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "wfballistics";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    public WFBallistics(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        ModEntities.register(modEventBus);
        com.wf.wfballistics.block.ModBlocks.register(modEventBus);
        com.wf.wfballistics.block.ModBlockEntities.register(modEventBus);
        com.wf.wfballistics.client.particle.WFParticles.register(modEventBus);
        com.wf.wfballistics.fluid.WFFluids.register(modEventBus);
        com.wf.wfballistics.WFSounds.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        event.enqueueWork(() -> {
            com.wf.wfballistics.network.WFNetwork.register();
            com.wf.wfballistics.entity.mist.MistEffects.bootstrap();
            net.minecraftforge.common.world.ForgeChunkManager.setForcedChunkLoadingCallback(
                    MODID, com.wf.wfballistics.chunk.WFChunkValidation::validate);
        });
    }
}
