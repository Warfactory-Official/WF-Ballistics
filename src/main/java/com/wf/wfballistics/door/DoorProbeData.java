package com.wf.wfballistics.door;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.probe.ProbeCapabilities;
import com.wf.wfballistics.probe.ProbeDataProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The one thing about a door the client is not told: how many of its blocks are being powered. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class DoorProbeData {

    public static final String POWERED = "PoweredBlocks";

    private DoorProbeData() {
    }

    private static final ProbeDataProvider.Blocks DATA = (data, player, level, pos, state, blockEntity) -> {
        net.minecraft.core.BlockPos core = DoorFrame.findCore(level, pos, state.getBlock());
        if (core != null && level.getBlockEntity(core) instanceof DoorBlockEntity door) {
            data.putInt(POWERED, door.poweredBlocks());
        }
    };

    @SubscribeEvent
    static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(ProbeCapabilities.BLOCK_DATA, (level, pos, state, be, ctx) -> DATA,
                ModDoors.DOORS.values().stream().map(holder -> (Block) holder.get()).toArray(Block[]::new));
    }

    public static int powered(CompoundTag data) {
        return data.getInt(POWERED);
    }
}
