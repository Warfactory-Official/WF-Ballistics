package com.wf.wflib.magazine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import java.util.List;

/**
 * Launch pod: {@link PodTubes}, round per tube, mixed kinds. Use: load empty tubes from the inventory; sneak-use: unload.
 *
 * @param tubes tube centres, metres, pod frame (+Z = nose, origin = tube-pattern centre)
 * @param length round length a tube takes
 * @param accepts item tag a tube round must carry
 */
public class PodItem extends Item {
    private final List<Vector2fc> tubes;
    private final float length;
    private final TagKey<Item> accepts;

    public PodItem(List<Vector2fc> tubes, float length, ResourceLocation accepts, Properties properties) {
        super(properties.stacksTo(1).component(MagazineItems.POD_TUBES.get(), PodTubes.empty(tubes.size())));
        this.tubes = List.copyOf(tubes);
        this.length = length;
        this.accepts = TagKey.create(Registries.ITEM, accepts);
    }

    public List<Vector2fc> tubes() {
        return tubes;
    }

    public float length() {
        return length;
    }

    public TagKey<Item> tag() {
        return accepts;
    }

    public boolean accepts(ItemStack round) {
        return !round.isEmpty() && round.is(accepts);
    }

    public static PodTubes contents(ItemStack pod) {
        return java.util.Objects.requireNonNull(pod.get(MagazineItems.POD_TUBES.get()), "pod_tubes");
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack pod = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(pod);
        }
        PodTubes c = contents(pod);
        Inventory inv = player.getInventory();
        int moved = 0;
        for (int t = 0; t < c.tubes().size(); t++) {
            if (player.isShiftKeyDown()) {
                if (!c.tubes().get(t).isEmpty()) {
                    inv.placeItemBackInInventory(c.tubes().get(t).copy());
                    c = c.with(t, ItemStack.EMPTY);
                    moved++;
                }
            } else if (c.tubes().get(t).isEmpty()) {
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (s != pod && accepts(s)) {
                        c = c.with(t, s.split(1));
                        moved++;
                        break;
                    }
                }
            }
        }
        pod.set(MagazineItems.POD_TUBES.get(), c);
        return moved > 0 ? InteractionResultHolder.consume(pod) : InteractionResultHolder.pass(pod);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        PodTubes c = contents(stack);
        tooltip.add(Component.literal(c.loaded() + " / " + tubes.size()).withStyle(ChatFormatting.GRAY));
        for (ItemStack round : c.tubes()) {
            if (!round.isEmpty()) {
                tooltip.add(round.getHoverName().copy().withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    static Vector2fc at(float x, float y) {
        return new Vector2f(x, y);
    }
}
