package com.wf.wflib.door;

import com.wf.wflib.WFLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registration for the whole door feature: fifteen blocks, their items, the one block entity type they share, and
 * the lock and key that work them.
 */
public final class ModDoors {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, WFLib.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, WFLib.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WFLib.MODID);

    public static final Map<DoorType, DeferredHolder<Block, DoorBlock>> DOORS =
            new EnumMap<>(DoorType.class);
    public static final Map<DoorType, DeferredHolder<Item, DoorItem>> DOOR_ITEMS =
            new EnumMap<>(DoorType.class);

    static {
        for (DoorType type : DoorType.values()) {
            DeferredHolder<Block, DoorBlock> block = BLOCKS.register(type.id(), () -> new DoorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .sound(SoundType.NETHERITE_BLOCK)
                            .strength(hardness(type), 1_000.0f)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .dynamicShape()
                            .pushReaction(PushReaction.BLOCK)
                            .noLootTable(),
                    type));
            DOORS.put(type, block);
            DOOR_ITEMS.put(type, ITEMS.register(type.id(),
                    () -> new DoorItem(block.get(), new Item.Properties())));
        }
    }

    /** One block entity type for every door, because they differ by table entry rather than by class. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DoorBlockEntity>> DOOR =
            BLOCK_ENTITIES.register("door", () -> {
                Block[] blocks = DOORS.values().stream().map(DeferredHolder::get).toArray(Block[]::new);
                return BlockEntityType.Builder.of(DoorBlockEntity::new, blocks).build(null);
            });

    public static final DeferredHolder<Item, DoorKeyItem> DOOR_KEY =
            ITEMS.register("door_key", () -> new DoorKeyItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, DoorLockItem> DOOR_LOCK =
            ITEMS.register("door_lock", () -> new DoorLockItem(new Item.Properties().stacksTo(1), 0.1));

    private ModDoors() {
    }

    /** Vault and secure doors are meant to resist being cut through; the rest are ordinary heavy metal. */
    private static float hardness(DoorType type) {
        return switch (type) {
            case SECURE_ACCESS_DOOR -> 20.0f;
            case WATER_DOOR, CARGO_DOOR -> 5.0f;
            default -> 10.0f;
        };
    }

    public static ResourceLocation id(DoorType type) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, type.id());
    }

    /** Every door in the creative tab, one entry per skin it can wear. */
    public static List<ItemStack> creativeStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (DoorType type : DoorType.values()) {
            DoorItem item = DOOR_ITEMS.get(type).get();
            for (int skin = 0; skin < Math.max(1, type.skins()); skin++) {
                stacks.add(DoorItem.withSkin(item, skin));
            }
        }
        return stacks;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        DoorComponents.register(modBus);
        DoorSounds.register(modBus);
        // A plain list add, so it needs no lifecycle event, and it has to happen on both sides.
        DoorProbeActions.register();
    }
}
