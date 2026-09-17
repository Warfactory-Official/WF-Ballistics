package com.wf.wfballistics.door;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.List;
import java.util.function.Consumer;

/** The item that places a door. */
public class DoorItem extends BlockItem {

    private final DoorType type;

    public DoorItem(DoorBlock block, Properties props) {
        super(block, props);
        this.type = block.type();
    }

    public DoorType type() {
        return type;
    }

    /** The skin index on a stack; 0 for one that has never been told. */
    public static int skinOf(ItemStack stack) {
        Integer skin = stack.get(DoorComponents.SKIN.get());
        return skin == null ? 0 : skin;
    }

    public static ItemStack withSkin(DoorItem item, int skin) {
        ItemStack stack = new ItemStack(item);
        stack.set(DoorComponents.SKIN.get(), skin);
        return stack;
    }

    @Override
    protected boolean canPlace(BlockPlaceContext ctx, BlockState state) {
        if (!super.canPlace(ctx, state)) {
            return false;
        }
        return getBlock() instanceof DoorBlock door
                && door.hasRoom(ctx.getLevel(), ctx.getClickedPos(), ctx.getHorizontalDirection());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, lines, flag);
        int[] dims = type.dimensions();
        lines.add(Component.translatable("tooltip.wfballistics.door.size",
                        dims[4] + dims[5] + 1, dims[1] + dims[0] + 1, dims[2] + dims[3] + 1)
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.wfballistics.door.redstone")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (type.skins() > 1) {
            lines.add(Component.translatable("tooltip.wfballistics.door.skin", skinOf(stack) + 1, type.skins())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** The item is the mesh; see {@code DoorItemRenderer}. */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return com.wf.wfballistics.door.client.DoorItemRenderer.instance();
            }
        });
    }
}
