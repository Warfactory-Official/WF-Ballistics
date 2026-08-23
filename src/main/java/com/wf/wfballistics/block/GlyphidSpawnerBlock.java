package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import com.wf.wfballistics.block.entity.GlyphidSpawnerBlockEntity;
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

/**
 * An egg chamber: the visible, breakable face of a colony record.
 *
 * <p>Per §4 the record is the truth and the blocks are only its view, so this block runs no logic of its
 * own — {@link GlyphidSpawnerBlockEntity} spends the colony's population to put defenders on the ground and
 * has nothing to say when there is no colony behind it.
 *
 * <p>Breaking one is the counterplay, and the reason the block layer is not decoration: a chamber dug out is
 * a chamber the record loses, and a colony that loses its last one stops existing. Without that, a razed nest
 * would keep growing and keep mustering warbands out of a mound the player had already cleared.
 */
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

    /**
     * Tell the colony it just lost a chamber.
     *
     * <p>Read before {@code super}, which is what discards the block entity — and so the only place the
     * chamber's binding to its colony still exists. The {@code is(newState)} guard keeps a state swap from
     * reading as a demolition.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof GlyphidSpawnerBlockEntity chamber) {
            chamber.onBroken(server);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
