package com.wf.wfballistics;

import com.wf.wfballistics.colony.GlyphidNest;
import com.wf.wfballistics.config.WFClientConfig;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.drone.DroneTracker;
import com.wf.wfballistics.drone.sim.SimDroneManager;
import com.wf.wfballistics.entity.mist.MistEffects;
import com.wf.wfballistics.recon.CountermeasureRegistry;
import com.wf.wfballistics.recon.ReconTargets;
import com.wf.wfballistics.recon.SignatureRegistry;
import com.wf.wfballistics.recon.example.ExampleCountermeasureProvider;
import com.wf.wfballistics.recon.example.ExampleSignatureProvider;
import com.wf.wfballistics.recon.example.decoy.DecoyTargetSource;
import com.wf.wfballistics.recon.source.EntityTargetSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@Mod(WFBallistics.MODID)
public class WFBallistics {
    public static final String MODID = "wfballistics";

    public WFBallistics(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(WFBallistics::registerCapabilities);

        ModEntities.register(modEventBus);
        com.wf.wfballistics.block.ModBlocks.register(modEventBus);
        com.wf.wfballistics.block.ModBlockEntities.register(modEventBus);
        com.wf.wfballistics.item.ModItems.register(modEventBus);
        com.wf.wfballistics.item.ModDataComponents.register(modEventBus);
        com.wf.wfballistics.WFCreativeTabs.register(modEventBus);
        com.wf.wfballistics.menu.ModMenus.register(modEventBus);
        com.wf.wfballistics.client.particle.WFParticles.register(modEventBus);
        com.wf.wfballistics.fluid.WFFluids.register(modEventBus);
        com.wf.wfballistics.WFSounds.register(modEventBus);
        com.wf.wfballistics.fire.WFFire.register(modEventBus);
        com.wf.wfballistics.door.ModDoors.register(modEventBus);
        // A plain list add, on both sides, for the same reason the doors' is.
        com.wf.wfballistics.mine.MineProbeActions.register();

        modEventBus.addListener(WFConfig::onLoad);
        modContainer.registerConfig(
                ModConfig.Type.COMMON, WFConfig.SPEC);
        modContainer.registerConfig(
                ModConfig.Type.CLIENT, WFClientConfig.SPEC);
        modContainer.registerConfig(
                ModConfig.Type.CLIENT, com.wf.wfballistics.probe.client.ProbeConfig.SPEC,
                "wfballistics-probe.toml");
    }

    /** Probes take FE from any side. */
    private static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                com.wf.wfballistics.block.ModBlockEntities.RECON_PROBE.get(),
                (probe, side) -> probe.energy());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                com.wf.wfballistics.block.ModBlockEntities.CARGO_SHUTTLE.get(),
                (shuttle, side) -> shuttle.items());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
           MistEffects.bootstrap();
           DroneTracker.bootstrap();
           SimDroneManager.bootstrap();
           ReconTargets.bootstrap();
           com.wf.wfballistics.orbital.SatPayloads.bootstrap();
           com.wf.wfballistics.orbital.CargoTable.bootstrap();
           com.wf.wfballistics.warhead.OrbitalWarheads.bootstrap();
           SignatureRegistry.register(new EntityTargetSource.BuiltIn());
           ReconTargets.addSource(new DecoyTargetSource());
           SignatureRegistry.register(new ExampleSignatureProvider());
           CountermeasureRegistry.register(new ExampleCountermeasureProvider());
           GlyphidNest.install();
        });
    }
}
