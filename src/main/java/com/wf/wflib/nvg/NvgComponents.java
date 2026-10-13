package com.wf.wflib.nvg;

import com.wf.wflib.WFLib;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class NvgComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);

    /** On the NVG stack, worn or mounted. Absent = {@link NvgState#UP}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<NvgState>> STATE =
            COMPONENTS.register("nvg_state", () -> DataComponentType.<NvgState>builder()
                    .persistent(NvgState.CODEC)
                    .networkSynchronized(NvgState.STREAM_CODEC)
                    .build());

    /** On a helmet: the mounted NVG, full stack. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> MOUNT =
            COMPONENTS.register("nvg_mount", () -> DataComponentType.<ItemContainerContents>builder()
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC)
                    .build());

    private NvgComponents() {
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }
}
