package com.wf.wfballistics.block;

import com.wf.wfballistics.block.entity.MissileListenerDebugBlockEntity;
import com.wf.wfballistics.sim.SimMissile;
import com.wf.wfballistics.sim.SimMissileManager;
import com.wf.wfballistics.sim.SimMissileRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Debug block that acts as a missile listener (via its block entity) and, on right-click, launches a
 * simulated interceptor at the nearest simulated missile — a test harness for both listener respawns
 * and simulated interception.
 */
public class MissileListenerDebugBlock extends BaseEntityBlock {
    public MissileListenerDebugBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MissileListenerDebugBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.MISSILE_LISTENER_DEBUG.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level instanceof ServerLevel sl) {
            SimMissileRegistry reg = SimMissileRegistry.get(sl);
            Vec3 center = Vec3.atCenterOf(pos);
            SimMissile nearest = null;
            double best = Double.MAX_VALUE;
            for (SimMissile sm : reg.view()) {
                if (sm.role != SimMissile.Role.NORMAL) {
                    continue;
                }
                double d = sm.pos.distanceToSqr(center);
                if (d < best) {
                    best = d;
                    nearest = sm;
                }
            }
            if (nearest != null) {
                SimMissileManager.launchInterceptor(sl, center.add(0.0, 4.0, 0.0), nearest.id);
                player.displayClientMessage(Component.literal("Interceptor launched at simulated missile."), true);
            } else {
                player.displayClientMessage(Component.literal("No simulated missiles to intercept."), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
