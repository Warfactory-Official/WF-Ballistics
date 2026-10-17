package com.wf.wflib.magazine;

import com.wf.wflib.ammo.Ammo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** Built-in box / can / drum. Use: fill from the inventory; sneak-use: unload. */
public class MagazineItem extends Item implements Magazine {
    private final ResourceLocation calibre;
    private final int capacity;

    public MagazineItem(ResourceLocation calibre, int capacity, Properties properties) {
        super(properties.stacksTo(1).component(MagazineItems.CONTENTS.get(), MagContents.EMPTY));
        this.calibre = calibre;
        this.capacity = capacity;
    }

    @Override
    public ResourceLocation calibre() {
        return calibre;
    }

    @Override
    public int perLink() {
        return capacity;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        RoundStore store = new PlayerRounds(player);
        int moved = player.isShiftKeyDown() ? MagFill.unload(store, stack) : MagFill.fill(store, stack);
        return moved > 0 ? InteractionResultHolder.consume(stack) : InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        MagContents c = MagFill.contents(stack);
        tooltip.add(Component.literal(c.total() + " / " + c.capacity(capacity) + " " + Ammo.calibre(calibre).name())
                .withStyle(ChatFormatting.GRAY));
        for (Run r : c.runs().reversed()) {
            tooltip.add(Component.literal(r.count() + "x " + Ammo.variant(r.round()).name())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private float fill(ItemStack stack) {
        MagContents c = MagFill.contents(stack);
        return (float) c.total() / c.capacity(capacity);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return fill(stack) < 1;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13 * fill(stack));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(fill(stack) / 3, 1, 1);
    }
}
