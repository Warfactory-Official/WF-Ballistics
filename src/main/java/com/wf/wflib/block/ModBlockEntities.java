package com.wf.wflib.block;

import com.wf.wflib.WFLib;
import com.wf.wflib.block.entity.*;
import com.wf.wflib.demolition.MiningChargeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WFLib.MODID);

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }

    /** Shared by both charge tiers; stores the placer's UUID so the sneak overlay knows whose charge it is. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MiningChargeBlockEntity>> MINING_CHARGE =
            BLOCK_ENTITIES.register("mining_charge", () -> BlockEntityType.Builder.of(
                    MiningChargeBlockEntity::new,
                    ModBlocks.MINING_CHARGE.get(), ModBlocks.DEEP_MINING_CHARGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DronePadBlockEntity>> DRONE_PAD =
            BLOCK_ENTITIES.register("drone_pad", () -> BlockEntityType.Builder.of(
                    DronePadBlockEntity::new, ModBlocks.DRONE_PAD.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CargoShuttleBlockEntity>> CARGO_SHUTTLE =
            BLOCK_ENTITIES.register("cargo_shuttle", () -> BlockEntityType.Builder.of(
                    CargoShuttleBlockEntity::new, ModBlocks.CARGO_SHUTTLE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LandingPadBlockEntity>> LANDING_PAD =
            BLOCK_ENTITIES.register("landing_pad", () -> BlockEntityType.Builder.of(
                    LandingPadBlockEntity::new, ModBlocks.LANDING_PAD.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OrbitalJammerBlockEntity>> ORBITAL_JAMMER =
            BLOCK_ENTITIES.register("orbital_jammer", () -> BlockEntityType.Builder.of(
                    OrbitalJammerBlockEntity::new, ModBlocks.ORBITAL_JAMMER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MissileListenerDebugBlockEntity>> MISSILE_LISTENER_DEBUG =
            BLOCK_ENTITIES.register("missile_listener_debug", () -> BlockEntityType.Builder.of(
                    MissileListenerDebugBlockEntity::new, ModBlocks.MISSILE_LISTENER_DEBUG.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MissileDispenserBlockEntity>> MISSILE_DISPENSER =
            BLOCK_ENTITIES.register("missile_dispenser", () -> BlockEntityType.Builder.of(
                    MissileDispenserBlockEntity::new, ModBlocks.MISSILE_DISPENSER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretCiwsBlockEntity>> TURRET_CIWS =
            BLOCK_ENTITIES.register("turret_ciws", () -> BlockEntityType.Builder.of(
                    TurretCiwsBlockEntity::new, ModBlocks.TURRET_CIWS.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretInterceptorNormalBlockEntity>> TURRET_INTERCEPTOR =
            BLOCK_ENTITIES.register("turret_interceptor", () -> BlockEntityType.Builder.of(
                    TurretInterceptorNormalBlockEntity::new, ModBlocks.TURRET_INTERCEPTOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretInterceptorSupersonicBlockEntity>> TURRET_INTERCEPTOR_SUPERSONIC =
            BLOCK_ENTITIES.register("turret_interceptor_supersonic", () -> BlockEntityType.Builder.of(
                    TurretInterceptorSupersonicBlockEntity::new, ModBlocks.TURRET_INTERCEPTOR_SUPERSONIC.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GlyphidSpawnerBlockEntity>> GLYPHID_SPAWNER =
            BLOCK_ENTITIES.register("glyphid_spawner", () -> BlockEntityType.Builder.of(
                    GlyphidSpawnerBlockEntity::new, ModBlocks.GLYPHID_SPAWNER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarSurveillanceBlockEntity>> RADAR_SURVEILLANCE =
            BLOCK_ENTITIES.register("radar_surveillance", () -> BlockEntityType.Builder.of(
                    RadarSurveillanceBlockEntity::new, ModBlocks.RADAR_SURVEILLANCE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarScopeBlockEntity>> RADAR_SCOPE =
            BLOCK_ENTITIES.register("radar_scope", () -> BlockEntityType.Builder.of(
                    RadarScopeBlockEntity::new, ModBlocks.RADAR_SCOPE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CameraMonitorBlockEntity>> CAMERA_MONITOR =
            BLOCK_ENTITIES.register("camera_monitor", () -> BlockEntityType.Builder.of(
                    CameraMonitorBlockEntity::new, ModBlocks.CAMERA_MONITOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ReconHubBlockEntity>> RECON_HUB =
            BLOCK_ENTITIES.register("recon_hub", () -> BlockEntityType.Builder.of(
                    ReconHubBlockEntity::new, ModBlocks.RECON_HUB.get()).build(null));

    /** One type for every probe. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ReconProbeBlockEntity>> RECON_PROBE =
            BLOCK_ENTITIES.register("recon_probe", () -> BlockEntityType.Builder.of(
                    ReconProbeBlockEntity::new, probeBlocks()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GridPowerCellBlockEntity>> GRID_POWER_CELL =
            BLOCK_ENTITIES.register("grid_power_cell", () -> BlockEntityType.Builder.of(
                    GridPowerCellBlockEntity::new, ModBlocks.GRID_POWER_CELL.get()).build(null));

    private static net.minecraft.world.level.block.Block[] probeBlocks() {
        return ModBlocks.PROBES.values().stream()
                .map(DeferredHolder::get)
                .toArray(net.minecraft.world.level.block.Block[]::new);
    }
}
