package com.wf.wflib.item;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.FeedTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.UUID;

/** The mod's item data components: what a camera receiver, a detonator or a lifted mine rack is carrying. */
public final class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);

    /** The bound cameras on a handheld receiver. The monitor block keeps the same type in its own NBT. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CameraChannels>> CHANNELS =
            COMPONENTS.register("camera_channels", () -> DataComponentType.<CameraChannels>builder()
                    .persistent(CameraChannels.CODEC)
                    .networkSynchronized(CameraChannels.STREAM_CODEC)
                    .build());

    /** The network a {@link GridKeyItem} is holding. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> GRID_ID =
            COMPONENTS.register("grid_id", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    /** What a linker is currently holding, between picking a camera up and putting it down on a panel. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FeedTarget>> LINK_TARGET =
            COMPONENTS.register("camera_link_target", () -> DataComponentType.<FeedTarget>builder()
                    .persistent(FeedTarget.CODEC)
                    .networkSynchronized(FeedTarget.STREAM_CODEC)
                    .build());

    /** Block positions of the mining charges a detonator is wired to. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<BlockPos>>> DETONATOR_CHARGES =
            COMPONENTS.register("detonator_charges", () -> DataComponentType.<List<BlockPos>>builder()
                    .persistent(BlockPos.CODEC.listOf())
                    .networkSynchronized(BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    /**
     * Ids of the detonatable entities a detonator is wired to: mines, and anything else that implements {@link
     * com.wf.wflib.demolition.IDetonatableEntity}.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<UUID>>> DETONATOR_MINES =
            COMPONENTS.register("detonator_mines", () -> DataComponentType.<List<UUID>>builder()
                    .persistent(UUIDUtil.CODEC.listOf())
                    .networkSynchronized(UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    /** How many canisters were left on a rack when it was lifted. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MINE_CANISTERS =
            COMPONENTS.register("mine_canisters", () -> DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    private ModDataComponents() {
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }
}
