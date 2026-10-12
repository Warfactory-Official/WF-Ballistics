package com.wf.wflib.probe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** The two things a probe can be pointed at, and what a provider does about each. */
public final class ProbeProvider {

    private ProbeProvider() {
    }

    /** Adds to the panel for a block. Runs client side, on the client's copy of the world. */
    @FunctionalInterface
    public interface Blocks {
        void append(ProbeInfo info, ProbeContext ctx, BlockState state, BlockPos pos,
                    @Nullable BlockEntity blockEntity);
    }

    /** Adds to the panel for an entity. */
    @FunctionalInterface
    public interface Entities {
        void append(ProbeInfo info, ProbeContext ctx, Entity entity);
    }

    /** Nodes on an entity; client, per frame, while within reach. */
    @FunctionalInterface
    public interface EntityNodes {
        void nodes(java.util.List<ProbeNode> out, ProbeContext ctx, Entity entity);
    }

    /** Nodes on a block entity; client, per frame, while within reach. */
    @FunctionalInterface
    public interface BlockNodes {
        void nodes(java.util.List<ProbeNode> out, ProbeContext ctx, BlockEntity blockEntity);
    }
}
