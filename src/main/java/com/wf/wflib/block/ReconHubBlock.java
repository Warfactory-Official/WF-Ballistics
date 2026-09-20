package com.wf.wflib.block;

import com.mojang.serialization.MapCodec;
import com.wf.wflib.block.entity.ReconHubBlockEntity;
import com.wf.wflib.client.gui.ScopeScreenOpener;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/** The grid hub: the block a network is. */
public class ReconHubBlock extends BaseEntityBlock {

    public static final MapCodec<ReconHubBlock> CODEC = simpleCodec(ReconHubBlock::new);
    /** Nodes listed in a single right-click before it starts truncating. */
    private static final int LIST_LIMIT = 12;

    public ReconHubBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ReconHubBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Comparator output is the fraction of the grid that is online, so a hub can drive a warning light when a relay
     * is cut without anybody writing a redstone contraption to notice.
     */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ReconHubBlockEntity hub)) {
            return 0;
        }
        ReconGrid grid = hub.grid();
        if (grid == null || grid.nodeCount() == 0) {
            return 0;
        }
        return Math.max(1, grid.onlineCount() * 15 / grid.nodeCount());
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.RECON_HUB.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide && !player.isShiftKeyDown()
                && level.getBlockEntity(pos) instanceof ReconHubBlockEntity hub) {
            ScopeScreenOpener.open(hub.netId(), pos.getX() + 0.5, pos.getZ() + 0.5);
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide && player.isShiftKeyDown()
                && level.getBlockEntity(pos) instanceof ReconHubBlockEntity hub) {
            this.report(player, hub);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private void report(Player player, ReconHubBlockEntity hub) {
        UUID id = hub.gridId();
        player.displayClientMessage(Component.literal("Grid " + (id == null ? "(unclaimed)" : id))
                .withStyle(ChatFormatting.GOLD), false);
        ReconGrid grid = hub.grid();
        if (grid == null) {
            player.displayClientMessage(Component.literal("  no network yet")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return;
        }
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "  net %s: %d/%d node(s) online, %d hub(s), %d track(s)",
                Long.toHexString(hub.netId()), grid.onlineCount(), grid.nodeCount(),
                grid.rootCount(), hub.picture().tracks().size())), false);

        int shown = 0;
        for (GridNode node : grid.nodes()) {
            if (node.root()) {
                continue;
            }
            if (shown++ >= LIST_LIMIT) {
                player.displayClientMessage(Component.literal("  ...and " + (grid.nodeCount() - shown) + " more")
                        .withStyle(ChatFormatting.DARK_GRAY), false);
                break;
            }
            String where = node.pos().toShortString();
            if (!node.online()) {
                player.displayClientMessage(Component.literal(
                                String.format(Locale.ROOT, "  %-8s %-18s ORPHANED", node.label(), where))
                        .withStyle(ChatFormatting.RED), false);
                continue;
            }
            player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                    "  %-8s %-18s %d hop(s)%s", node.label(), where, node.hops(),
                    node.downstream() > 0 ? "  carrying " + node.downstream() : "")), false);
        }
    }
}
