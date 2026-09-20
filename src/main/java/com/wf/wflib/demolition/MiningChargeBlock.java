package com.wf.wflib.demolition;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A demolition charge that mines a cube of natural blocks when set off by a detonator. */
public class MiningChargeBlock extends Block implements IDetonatable, EntityBlock {

    /** Set once the charge has been linked to a detonator; swaps to the "armed" texture set. */
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");

    private final int radius;
    private final int fortune;
    private final int tier;

    public MiningChargeBlock(Properties properties, int radius, int fortune, int tier) {
        super(properties);
        this.radius = radius;
        this.fortune = fortune;
        this.tier = tier;
        registerDefaultState(stateDefinition.any()
                .setValue(ARMED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ARMED);
    }

    public int getRadius() {
        return radius;
    }

    public int getTier() {
        return tier;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MiningChargeBlockEntity(pos, state);
    }

    /** Remember who placed the charge: its UUID is kept for the sneak-outline overlay. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer != null
                && level.getBlockEntity(pos) instanceof MiningChargeBlockEntity be) {
            be.setPlacer(placer.getUUID());
        }
    }

    @Override
    public void detonate(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player detonator) {
        level.removeBlock(pos, false);
        MiningExplosion.explode(level, pos, radius, fortune, tier, detonator);
    }

    /** Never ignited or consumed by fire: the only way to set it off is a detonator. */
    @Override
    public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("wflib.tooltip.mining_charge")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("wflib.tooltip.mining_charge.tier", tier)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
