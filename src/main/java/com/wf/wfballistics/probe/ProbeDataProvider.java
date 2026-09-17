package com.wf.wfballistics.probe;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** The server half, for state the client does not have. */
public final class ProbeDataProvider {

    private ProbeDataProvider() {
    }

    @FunctionalInterface
    public interface Blocks {
        void append(CompoundTag data, ServerPlayer player, Level level, BlockPos pos, BlockState state,
                    @Nullable BlockEntity blockEntity);
    }

    @FunctionalInterface
    public interface Entities {
        void append(CompoundTag data, ServerPlayer player, Entity entity);
    }
}
