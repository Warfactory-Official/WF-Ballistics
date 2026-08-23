package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
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

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, WFBallistics.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, WFBallistics.MODID);

    public static final DeferredHolder<Block, Block> MISSILE_LISTENER_DEBUG =
            BLOCKS.register("missile_listener_debug", () -> new MissileListenerDebugBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item>MISSILE_LISTENER_DEBUG_ITEM =
            ITEMS.register("missile_listener_debug", () ->
                    new BlockItem(MISSILE_LISTENER_DEBUG.get(), new Item.Properties()));

    // Drone launch/recovery pad: holds cargo + a mission, dispatches on a redstone edge, recharges drones
    // parked on top.
    public static final DeferredHolder<Block, Block> DRONE_PAD =
            BLOCKS.register("drone_pad", () -> new DronePadBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final DeferredHolder<Item, Item> DRONE_PAD_ITEM =
            ITEMS.register("drone_pad", () -> new BlockItem(DRONE_PAD.get(), new Item.Properties()));

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

    // A colony's blocks. Soft and quiet -- a nest is flesh, not masonry -- and dropping nothing: the mound
    // is a colony's body, and a player clearing one is not harvesting it.
    public static final DeferredHolder<Block, Block> GLYPHID_NEST =
            BLOCKS.register("glyphid_nest", () -> new GlyphidNestBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE)
                            .strength(0.5f).sound(SoundType.WOOL).noLootTable()));

    public static final DeferredHolder<Item, Item> GLYPHID_NEST_ITEM =
            ITEMS.register("glyphid_nest", () ->
                    new BlockItem(GLYPHID_NEST.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> GLYPHID_SPAWNER =
            BLOCKS.register("glyphid_spawner", () -> new GlyphidSpawnerBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE)
                            .strength(0.5f).sound(SoundType.WOOL).noLootTable()));

    public static final DeferredHolder<Item, Item> GLYPHID_SPAWNER_ITEM =
            ITEMS.register("glyphid_spawner", () ->
                    new BlockItem(GLYPHID_SPAWNER.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
