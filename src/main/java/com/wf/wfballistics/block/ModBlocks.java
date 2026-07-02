package com.wf.wfballistics.block;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = WFBallistics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
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

    public static final RegistryObject<Block> TURRET_CIWS =
            BLOCKS.register("turret_ciws", () -> new TurretCiwsBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f).requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> TURRET_CIWS_ITEM =
            ITEMS.register("turret_ciws", () ->
                    new BlockItem(TURRET_CIWS.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(MISSILE_LISTENER_DEBUG_ITEM.get());
            event.accept(TURRET_CIWS_ITEM.get());
        }
    }
}
