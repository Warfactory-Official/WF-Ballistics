package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.demolition.MiningChargeBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, WFBallistics.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, WFBallistics.MODID);

    public static final DeferredHolder<Block, Block> MINING_CHARGE =
            BLOCKS.register("mining_charge", () -> new MiningChargeBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.FIRE).strength(0.8f)
                            .sound(SoundType.METAL)
                            .isValidSpawn((state, level, pos, ent) -> false), 3, 2, 1));

    public static final DeferredHolder<Item, Item> MINING_CHARGE_ITEM =
            ITEMS.register("mining_charge", () -> new BlockItem(MINING_CHARGE.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> DEEP_MINING_CHARGE =
            BLOCKS.register("deep_mining_charge", () -> new MiningChargeBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(0.8f)
                            .sound(SoundType.METAL)
                            .isValidSpawn((state, level, pos, ent) -> false), 3, 2, 2));

    public static final DeferredHolder<Item, Item> DEEP_MINING_CHARGE_ITEM =
            ITEMS.register("deep_mining_charge",
                    () -> new BlockItem(DEEP_MINING_CHARGE.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> MISSILE_LISTENER_DEBUG =
            BLOCKS.register("missile_listener_debug", () -> new MissileListenerDebugBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item>MISSILE_LISTENER_DEBUG_ITEM =
            ITEMS.register("missile_listener_debug", () ->
                    new BlockItem(MISSILE_LISTENER_DEBUG.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> DRONE_PAD =
            BLOCKS.register("drone_pad", () -> new DronePadBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item> DRONE_PAD_ITEM =
            ITEMS.register("drone_pad", () -> new BlockItem(DRONE_PAD.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> CARGO_SHUTTLE =
            BLOCKS.register("cargo_shuttle", () -> new CargoShuttleBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0f)));

    public static final DeferredHolder<Item, Item> CARGO_SHUTTLE_ITEM =
            ITEMS.register("cargo_shuttle", () -> new BlockItem(CARGO_SHUTTLE.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> LANDING_PAD =
            BLOCKS.register("landing_pad", () -> new LandingPadBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item> LANDING_PAD_ITEM =
            ITEMS.register("landing_pad", () -> new BlockItem(LANDING_PAD.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> ORBITAL_JAMMER =
            BLOCKS.register("orbital_jammer", () -> new OrbitalJammerBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.0f)));

    public static final DeferredHolder<Item, Item> ORBITAL_JAMMER_ITEM =
            ITEMS.register("orbital_jammer", () -> new BlockItem(ORBITAL_JAMMER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block>MISSILE_DISPENSER =
            BLOCKS.register("missile_dispenser", () -> new MissileDispenserBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item>MISSILE_DISPENSER_ITEM =
            ITEMS.register("missile_dispenser", () ->
                    new BlockItem(MISSILE_DISPENSER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block>TURRET_CIWS =
            BLOCKS.register("turret_ciws", () -> new TurretCiwsBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, Item>TURRET_CIWS_ITEM =
            ITEMS.register("turret_ciws", () ->
                    new BlockItem(TURRET_CIWS.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block>TURRET_INTERCEPTOR =
            BLOCKS.register("turret_interceptor", () -> new TurretInterceptorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops(),
                    false));

    public static final DeferredHolder<Item, Item>TURRET_INTERCEPTOR_ITEM =
            ITEMS.register("turret_interceptor", () ->
                    new BlockItem(TURRET_INTERCEPTOR.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block>TURRET_INTERCEPTOR_SUPERSONIC =
            BLOCKS.register("turret_interceptor_supersonic", () -> new TurretInterceptorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops(),
                    true));

    public static final DeferredHolder<Item, Item>TURRET_INTERCEPTOR_SUPERSONIC_ITEM =
            ITEMS.register("turret_interceptor_supersonic", () ->
                    new BlockItem(TURRET_INTERCEPTOR_SUPERSONIC.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> GLYPHID_NEST =
            BLOCKS.register("glyphid_nest", () -> new GlyphidNestBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE)
                            .strength(0.5f).sound(SoundType.WOOL).noLootTable()));

    public static final DeferredHolder<Item, Item> GLYPHID_NEST_ITEM =
            ITEMS.register("glyphid_nest", () ->
                    new BlockItem(GLYPHID_NEST.get(), new Item.Properties()));

    /** The same flesh, hardened, laid once the world has evolved past {@code colonies.reinforcedEvolution}. */
    public static final DeferredHolder<Block, Block> GLYPHID_NEST_REINFORCED =
            BLOCKS.register("glyphid_nest_reinforced", () -> new GlyphidNestBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN)
                            .strength(2.5f, 30.0f).sound(SoundType.WOOL).noLootTable()));

    public static final DeferredHolder<Item, Item> GLYPHID_NEST_REINFORCED_ITEM =
            ITEMS.register("glyphid_nest_reinforced", () ->
                    new BlockItem(GLYPHID_NEST_REINFORCED.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> GLYPHID_SPAWNER =
            BLOCKS.register("glyphid_spawner", () -> new GlyphidSpawnerBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE)
                            .strength(0.5f).sound(SoundType.WOOL).noLootTable()));

    public static final DeferredHolder<Item, Item> GLYPHID_SPAWNER_ITEM =
            ITEMS.register("glyphid_spawner", () ->
                    new BlockItem(GLYPHID_SPAWNER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> RADAR_SURVEILLANCE =
            BLOCKS.register("radar_surveillance", () -> new RadarSurveillanceBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item> RADAR_SURVEILLANCE_ITEM =
            ITEMS.register("radar_surveillance", () ->
                    new BlockItem(RADAR_SURVEILLANCE.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> RADAR_SCOPE =
            BLOCKS.register("radar_scope", () -> new RadarScopeBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0f)));

    public static final DeferredHolder<Item, Item> RADAR_SCOPE_ITEM =
            ITEMS.register("radar_scope", () ->
                    new BlockItem(RADAR_SCOPE.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> CAMERA_MONITOR =
            BLOCKS.register("camera_monitor", () -> new CameraMonitorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0f)));

    public static final DeferredHolder<Item, Item> CAMERA_MONITOR_ITEM =
            ITEMS.register("camera_monitor", () ->
                    new BlockItem(CAMERA_MONITOR.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> SECURITY_CAMERA =
            BLOCKS.register("security_camera", () -> new SecurityCameraBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5f)
                            .noOcclusion()));

    public static final DeferredHolder<Item, Item> SECURITY_CAMERA_ITEM =
            ITEMS.register("security_camera", () ->
                    new BlockItem(SECURITY_CAMERA.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> RECON_HUB =
            BLOCKS.register("recon_hub", () -> new ReconHubBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(4.0f)));

    public static final DeferredHolder<Item, Item> RECON_HUB_ITEM =
            ITEMS.register("recon_hub", () -> new BlockItem(RECON_HUB.get(), new Item.Properties()));

    /** One holder per {@link ProbeKind}, because a block is a registry object and an enum constant is not. */
    public static final Map<ProbeKind, DeferredHolder<Block, Block>> PROBES = new EnumMap<>(ProbeKind.class);
    public static final Map<ProbeKind, DeferredHolder<Item, Item>> PROBE_ITEMS = new EnumMap<>(ProbeKind.class);

    static {
        for (ProbeKind kind : ProbeKind.values()) {
            // The hydrophone waterlogs and the other four do not, which is the only thing the kinds differ by
            // beyond their numbers.
            DeferredHolder<Block, Block> block = BLOCKS.register(kind.blockName(), () -> {
                BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                        .mapColor(kind.underwater() ? MapColor.WATER : MapColor.METAL).strength(2.5f);
                return kind.underwater() ? new SonarProbeBlock(props, kind) : new ReconProbeBlock(props, kind);
            });
            PROBES.put(kind, block);
            PROBE_ITEMS.put(kind, ITEMS.register(kind.blockName(),
                    () -> new BlockItem(block.get(), new Item.Properties())));
        }
    }

    public static final DeferredHolder<Block, Block> GRID_POWER_CELL =
            BLOCKS.register("grid_power_cell", () -> new GridPowerCellBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(2.0f)));

    public static final DeferredHolder<Item, Item> GRID_POWER_CELL_ITEM =
            ITEMS.register("grid_power_cell", () ->
                    new BlockItem(GRID_POWER_CELL.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
