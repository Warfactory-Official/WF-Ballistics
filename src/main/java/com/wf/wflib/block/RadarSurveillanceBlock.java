package com.wf.wflib.block;

import com.mojang.serialization.MapCodec;
import com.wf.wflib.block.entity.RadarSurveillanceBlockEntity;
import com.wf.wflib.client.gui.ScopeScreenOpener;
import com.wf.wflib.recon.track.Track;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Example content: a standing surveillance radar with no weapon on it. */
public class RadarSurveillanceBlock extends BaseEntityBlock {

    public static final MapCodec<RadarSurveillanceBlock> CODEC = simpleCodec(RadarSurveillanceBlock::new);
    /** Drives the model swap, so a dish that is shouting looks different from one that is not. */
    public static final BooleanProperty ALERTING = BooleanProperty.create("alerting");

    public RadarSurveillanceBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any().setValue(ALERTING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(ALERTING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadarSurveillanceBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                            Direction direction) {
        return level.getBlockEntity(pos) instanceof RadarSurveillanceBlockEntity be ? be.signal() : 0;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.RADAR_SURVEILLANCE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide && !player.isShiftKeyDown()
                && level.getBlockEntity(pos) instanceof RadarSurveillanceBlockEntity be) {
            ScopeScreenOpener.open(be.netId(), pos.getX() + 0.5, pos.getZ() + 0.5);
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide && player.isShiftKeyDown()
                && level.getBlockEntity(pos) instanceof RadarSurveillanceBlockEntity be) {
            player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                    "Radar net %s: %d track(s), %d block range, %d tick sweep",
                    Long.toHexString(be.netId()), be.trackCount(),
                    (int) RadarSurveillanceBlockEntity.RANGE, RadarSurveillanceBlockEntity.SWEEP_TICKS))
                    .withStyle(ChatFormatting.GOLD), false);
            Track nearest = be.nearest();
            if (nearest != null) {
                player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "  nearest: %s %s conf %.2f at %.0f,%.0f,%.0f +/-%.1f n=%d",
                        nearest.guess(), nearest.quality(), nearest.confidence(),
                        nearest.x(), nearest.y(), nearest.z(), nearest.errorRadius(),
                        nearest.countEstimate())), false);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
