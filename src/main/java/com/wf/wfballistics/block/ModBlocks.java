package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, WFBallistics.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, WFBallistics.MODID);

    public static final RegistryObject<Block> MISSILE_LISTENER_DEBUG =
            BLOCKS.register("missile_listener_debug", () -> new MissileListenerDebugBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final RegistryObject<Item> MISSILE_LISTENER_DEBUG_ITEM =
            ITEMS.register("missile_listener_debug", () ->
                    new BlockItem(MISSILE_LISTENER_DEBUG.get(), new Item.Properties()));

    public static final RegistryObject<Block> MISSILE_DISPENSER =
            BLOCKS.register("missile_dispenser", () -> new MissileDispenserBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f)));

    public static final RegistryObject<Item> MISSILE_DISPENSER_ITEM =
            ITEMS.register("missile_dispenser", () ->
                    new BlockItem(MISSILE_DISPENSER.get(), new Item.Properties()));

    public static final RegistryObject<Block> TURRET_CIWS =
            BLOCKS.register("turret_ciws", () -> new TurretCiwsBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> TURRET_CIWS_ITEM =
            ITEMS.register("turret_ciws", () ->
                    new BlockItem(TURRET_CIWS.get(), new Item.Properties()));

    public static final RegistryObject<Block> TURRET_INTERCEPTOR =
            BLOCKS.register("turret_interceptor", () -> new TurretInterceptorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops(),
                    false));

    public static final RegistryObject<Item> TURRET_INTERCEPTOR_ITEM =
            ITEMS.register("turret_interceptor", () ->
                    new BlockItem(TURRET_INTERCEPTOR.get(), new Item.Properties()));

    public static final RegistryObject<Block> TURRET_INTERCEPTOR_SUPERSONIC =
            BLOCKS.register("turret_interceptor_supersonic", () -> new TurretInterceptorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops(),
                    true));

    public static final RegistryObject<Item> TURRET_INTERCEPTOR_SUPERSONIC_ITEM =
            ITEMS.register("turret_interceptor_supersonic", () ->
                    new BlockItem(TURRET_INTERCEPTOR_SUPERSONIC.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
