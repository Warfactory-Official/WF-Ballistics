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

/** Condition/inserts components. See {@link ArmorStacks} for the vanilla-durability encoding they back. */
public final class ArmorComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);

    /** Worn remainder below the current durability point, {@code [0, POINTS_PER_DAMAGE)}. Absent = none. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> WEAR =
            COMPONENTS.register("armor_wear", () -> DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /** Condition remaining; used only when the item has no vanilla durability. Absent = pristine. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CONDITION =
            COMPONENTS.register("armor_condition", () -> DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /** Inserts fitted to this garment; each stack carries its own condition independent of the garment. */
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
