package com.wf.wflib;

import com.wf.wflib.colony.GlyphidNest;
import com.wf.wflib.config.WFClientConfig;
import com.wf.wflib.config.WFConfig;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.drone.sim.SimDroneManager;
import com.wf.wflib.entity.mist.MistEffects;
import com.wf.wflib.recon.CountermeasureRegistry;
import com.wf.wflib.recon.ReconTargets;
import com.wf.wflib.recon.SignatureRegistry;
import com.wf.wflib.recon.example.ExampleCountermeasureProvider;
import com.wf.wflib.recon.example.ExampleSignatureProvider;
import com.wf.wflib.recon.example.decoy.DecoyTargetSource;
import com.wf.wflib.recon.source.EntityTargetSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@Mod(WFLib.MODID)
public class WFLib {
    public static final String MODID = "wflib";

    public WFLib(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(WFLib::registerCapabilities);

        ModEntities.register(modEventBus);
        com.wf.wflib.block.ModBlocks.register(modEventBus);
        com.wf.wflib.block.ModBlockEntities.register(modEventBus);
        com.wf.wflib.item.ModItems.register(modEventBus);
        com.wf.wflib.item.ModDataComponents.register(modEventBus);
        com.wf.wflib.armor.ArmorComponents.register(modEventBus);
        com.wf.wflib.armor.ArmorMaterials.register(modEventBus);
        com.wf.wflib.WFCreativeTabs.register(modEventBus);
        com.wf.wflib.menu.ModMenus.register(modEventBus);
        com.wf.wflib.client.particle.WFParticles.register(modEventBus);
        com.wf.wflib.fluid.WFFluids.register(modEventBus);
        com.wf.wflib.WFSounds.register(modEventBus);
        com.wf.wflib.fire.WFFire.register(modEventBus);
        com.wf.wflib.door.ModDoors.register(modEventBus);
        // A plain list add, on both sides, for the same reason the doors' is.
        com.wf.wflib.mine.MineProbeActions.register();

        modEventBus.addListener(WFConfig::onLoad);
        modEventBus.addListener(com.wf.wflib.armor.ArmorConfig::onLoad);
        modContainer.registerConfig(
                ModConfig.Type.COMMON, WFConfig.SPEC);
        modContainer.registerConfig(
                ModConfig.Type.CLIENT, WFClientConfig.SPEC);
        modContainer.registerConfig(
                ModConfig.Type.CLIENT, com.wf.wflib.probe.client.ProbeConfig.SPEC,
                "wflib-probe.toml");
        // Its own file, so the armour package stays liftable out of the mod in one piece.
        modContainer.registerConfig(
                ModConfig.Type.COMMON, com.wf.wflib.armor.ArmorConfig.SPEC,
                "wflib-armor.toml");
        // Registers nothing at all unless Immersive Railroading is installed.
        com.wf.wflib.rail.RailFeatures.init(modContainer);
    }

    /** Probes take FE from any side. */
    private static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                com.wf.wflib.block.ModBlockEntities.RECON_PROBE.get(),
                (probe, side) -> probe.energy());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                com.wf.wflib.block.ModBlockEntities.CARGO_SHUTTLE.get(),
                (shuttle, side) -> shuttle.items());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
           MistEffects.bootstrap();
           com.wf.wflib.armor.ArmorExposure.bootstrap();
           com.wf.wflib.armor.ArmorPresets.bootstrap();
           DroneTracker.bootstrap();
           com.wf.wflib.sim.SimKinds.register(com.wf.wflib.sim.SimMissileManager.KIND);
           com.wf.wflib.sim.SimKinds.register(com.wf.wflib.round.Rounds.KIND);
           com.wf.wflib.sim.SimKinds.register(SimDroneManager.KIND);
           com.wf.wflib.sim.SimKinds.register(com.wf.wflib.entity.glyphid.sim.SimGlyphidManager.KIND);
           ReconTargets.bootstrap();
           com.wf.wflib.orbital.SatPayloads.bootstrap();
           com.wf.wflib.orbital.CargoTable.bootstrap();
           com.wf.wflib.warhead.OrbitalWarheads.bootstrap();
           SignatureRegistry.register(new EntityTargetSource.BuiltIn());
           ReconTargets.addSource(new DecoyTargetSource());
           SignatureRegistry.register(new ExampleSignatureProvider());
           CountermeasureRegistry.register(new ExampleCountermeasureProvider());
           GlyphidNest.install();
        });
    }
}
