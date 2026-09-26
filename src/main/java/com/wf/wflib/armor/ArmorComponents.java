package com.wf.wflib.armor;

import com.wf.wflib.WFLib;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Where a piece of armour keeps its condition and its inserts.
 *
 * <p>The design started from "bypass vanilla durability, because a stack has one damage value and a
 * carrier with three inserts needs three conditions". Implementation found a better answer to the
 * same problem: <b>put each insert on its own stack</b>, inside {@link #INSERTS}. Arity is solved,
 * and vanilla durability can then stay exactly where it is, so the durability bar, anvil repair and
 * Mending all keep working on every piece, foreign armour included.
 *
 * <p>Condition is still inflated a hundred to one, so {@link #WEAR} carries the part of the current
 * durability point that has been worn away but not yet spent. See {@link ArmorStacks}.
 */
public final class ArmorComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);

    /**
     * Condition points of the current durability point already worn away, 0 to
     * {@link ArmorUnits#POINTS_PER_DAMAGE} - 1. Absent means none.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> WEAR =
            COMPONENTS.register("armor_wear", () -> DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /**
     * Condition points remaining, for a piece with no vanilla durability of its own. Absent means
     * pristine.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CONDITION =
            COMPONENTS.register("armor_condition", () -> DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /**
     * The inserts fitted to this garment. Each is an ordinary stack carrying its own condition, which
     * is what makes a plate a consumable in front of an expensive carrier rather than part of it.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> INSERTS =
            COMPONENTS.register("armor_inserts", () -> DataComponentType.<ItemContainerContents>builder()
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC)
                    .build());

    private ArmorComponents() {
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }
}
