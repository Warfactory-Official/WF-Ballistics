package com.wf.wflib.probe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Who gets asked about what. */
public final class ProbeRegistry {

    private static final List<Entry<Predicate<BlockState>, ProbeProvider.Blocks>> BLOCKS = new ArrayList<>();
    private static final List<Entry<Predicate<Entity>, ProbeProvider.Entities>> ENTITIES = new ArrayList<>();
    private static final List<Entry<Predicate<BlockState>, ProbeDataProvider.Blocks>> BLOCK_DATA =
            new ArrayList<>();
    private static final List<Entry<Predicate<Entity>, ProbeDataProvider.Entities>> ENTITY_DATA =
            new ArrayList<>();

    /** The priority a "describe anything" provider registers at. */
    public static final int DEFAULT_PRIORITY = Integer.MIN_VALUE / 2;

    /** Where a capability's provider sits in the order. */
    public static final int CAPABILITY_PRIORITY = 0;

    private ProbeRegistry() {
    }

    private record Entry<T, P>(T test, P provider, int priority) {
    }

    private static <T, P> void insert(List<Entry<T, P>> into, T test, P provider, int priority) {
        into.add(new Entry<>(test, provider, priority));
        into.sort(Comparator.comparingInt(Entry::priority));
    }

    // --- client side -----------------------------------------------------------------------------

    public static void addBlocks(Predicate<BlockState> test, ProbeProvider.Blocks provider, int priority) {
        insert(BLOCKS, test, provider, priority);
    }

    public static void addBlock(Block block, ProbeProvider.Blocks provider) {
        addBlocks(state -> state.is(block), provider, CAPABILITY_PRIORITY);
    }

    /** Asked about every block. The fallback that makes the panel show for anything at all. */
    public static void addAnyBlock(ProbeProvider.Blocks provider, int priority) {
        addBlocks(state -> true, provider, priority);
    }

    public static void addEntities(Predicate<Entity> test, ProbeProvider.Entities provider, int priority) {
        insert(ENTITIES, test, provider, priority);
    }

    public static void addEntity(EntityType<?> type, ProbeProvider.Entities provider) {
        addEntities(entity -> entity.getType() == type, provider, CAPABILITY_PRIORITY);
    }

    public static void addAnyEntity(ProbeProvider.Entities provider, int priority) {
        addEntities(entity -> true, provider, priority);
    }

    // --- server side -----------------------------------------------------------------------------

    public static void addBlockData(Predicate<BlockState> test, ProbeDataProvider.Blocks provider,
                                    int priority) {
        insert(BLOCK_DATA, test, provider, priority);
    }

    public static void addEntityData(Predicate<Entity> test, ProbeDataProvider.Entities provider,
                                     int priority) {
        insert(ENTITY_DATA, test, provider, priority);
    }

    /** Whether anything at all wants server data about this block, so a request is worth sending. */
    public static boolean wantsBlockData(Level level, BlockPos pos, BlockState state,
                                         @Nullable BlockEntity blockEntity) {
        if (ProbeCapabilities.BLOCK_DATA.getCapability(level, pos, state, blockEntity, null) != null) {
            return true;
        }
        for (Entry<Predicate<BlockState>, ProbeDataProvider.Blocks> entry : BLOCK_DATA) {
            if (entry.test().test(state)) {
                return true;
            }
        }
        return false;
    }

    public static boolean wantsEntityData(Entity entity) {
        if (entity.getCapability(ProbeCapabilities.ENTITY_DATA) != null) {
            return true;
        }
        for (Entry<Predicate<Entity>, ProbeDataProvider.Entities> entry : ENTITY_DATA) {
            if (entry.test().test(entity)) {
                return true;
            }
        }
        return false;
    }

    // --- collection ------------------------------------------------------------------------------

    /**
     * Builds the panel for a block.
     *
     * @return whether the block is <em>supported</em>: it carries the probe capability, or a provider
     *      registered at anything but {@link #DEFAULT_PRIORITY} claimed it. Support is a matter of
     *      who claimed the target, not of whether they had anything to say about it this frame: a
     *      machine with nothing to report is still a machine, and its panel should not blink out.
     */
    public static boolean collect(ProbeInfo info, ProbeContext ctx, BlockState state, BlockPos pos,
                                  @Nullable BlockEntity blockEntity) {
        ProbeProvider.Blocks attached =
                ProbeCapabilities.BLOCK.getCapability(ctx.level(), pos, state, blockEntity, null);
        boolean supported = attached != null;
        boolean capabilityRun = attached == null;

        for (Entry<Predicate<BlockState>, ProbeProvider.Blocks> entry : BLOCKS) {
            if (!capabilityRun && entry.priority() > CAPABILITY_PRIORITY) {
                attached.append(info, ctx, state, pos, blockEntity);
                capabilityRun = true;
            }
            if (!entry.test().test(state)) {
                continue;
            }
            entry.provider().append(info, ctx, state, pos, blockEntity);
            supported |= entry.priority() != DEFAULT_PRIORITY;
        }
        if (!capabilityRun) {
            attached.append(info, ctx, state, pos, blockEntity);
        }
        return supported;
    }

    /** @see #collect(ProbeInfo, ProbeContext, BlockState, BlockPos, BlockEntity) */
    public static boolean collect(ProbeInfo info, ProbeContext ctx, Entity entity) {
        ProbeProvider.Entities attached = entity.getCapability(ProbeCapabilities.ENTITY);
        boolean supported = attached != null;
        boolean capabilityRun = attached == null;

        for (Entry<Predicate<Entity>, ProbeProvider.Entities> entry : ENTITIES) {
            if (!capabilityRun && entry.priority() > CAPABILITY_PRIORITY) {
                attached.append(info, ctx, entity);
                capabilityRun = true;
            }
            if (!entry.test().test(entity)) {
                continue;
            }
            entry.provider().append(info, ctx, entity);
            supported |= entry.priority() != DEFAULT_PRIORITY;
        }
        if (!capabilityRun) {
            attached.append(info, ctx, entity);
        }
        return supported;
    }

    public static void collectData(net.minecraft.nbt.CompoundTag data,
                                   net.minecraft.server.level.ServerPlayer player,
                                   Level level, BlockPos pos, BlockState state,
                                   @Nullable BlockEntity blockEntity) {
        ProbeDataProvider.Blocks attached =
                ProbeCapabilities.BLOCK_DATA.getCapability(level, pos, state, blockEntity, null);
        if (attached != null) {
            attached.append(data, player, level, pos, state, blockEntity);
        }
        for (Entry<Predicate<BlockState>, ProbeDataProvider.Blocks> entry : BLOCK_DATA) {
            if (entry.test().test(state)) {
                entry.provider().append(data, player, level, pos, state, blockEntity);
            }
        }
    }

    public static void collectData(net.minecraft.nbt.CompoundTag data,
                                   net.minecraft.server.level.ServerPlayer player, Entity entity) {
        ProbeDataProvider.Entities attached = entity.getCapability(ProbeCapabilities.ENTITY_DATA);
        if (attached != null) {
            attached.append(data, player, entity);
        }
        for (Entry<Predicate<Entity>, ProbeDataProvider.Entities> entry : ENTITY_DATA) {
            if (entry.test().test(entity)) {
                entry.provider().append(data, player, entity);
            }
        }
    }
}
