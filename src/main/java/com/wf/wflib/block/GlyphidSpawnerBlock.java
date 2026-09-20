package com.wf.wflib.block;

import com.mojang.serialization.MapCodec;
import com.wf.wflib.block.entity.GlyphidSpawnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** An egg chamber: the visible, breakable face of a colony record. */
public class GlyphidSpawnerBlock extends BaseEntityBlock {

    public static final MapCodec<GlyphidSpawnerBlock> CODEC = simpleCodec(GlyphidSpawnerBlock::new);

    public GlyphidSpawnerBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GlyphidSpawnerBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.GLYPHID_SPAWNER.get(),
                        GlyphidSpawnerBlockEntity::serverTick);
    }

    /** Tell the colony it just lost a chamber. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof GlyphidSpawnerBlockEntity chamber) {
            chamber.onBroken(server);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
