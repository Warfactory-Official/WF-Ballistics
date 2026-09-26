package com.wf.wflib.armor;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place that knows how a stack stores its condition, and the only place allowed to change it.
 *
 * <p>Condition is inflated a hundred to one so every wear calculation is exact integer arithmetic,
 * but the storage is still vanilla durability: {@code condition = (maxDamage - damage) * 100 - wear},
 * where {@code wear} is the part of the current durability point already spent. The bar moves, the
 * anvil repairs, and Mending works, because none of those ever learn that the resolution underneath
 * changed. A piece with no durability at all keeps its condition in a component instead.
 *
 * <p>Nothing here destroys an item. Armour at zero condition is dead armour, not missing armour: it
 * still supplies its {@link ArmorSpec#wornFloor()}, and it is still there to be repaired.
 */
public final class ArmorStacks {

    private ArmorStacks() {
    }

    /** @return the layer this stack contributes, or null if the stack is not armour we know */
    @Nullable
    public static ArmorLayer layer(ItemStack stack, double coverage) {
        ArmorSpec spec = ArmorProfiles.specFor(stack);
        return spec == null ? null : new ArmorLayer(spec, condition(stack, spec), coverage);
    }

    /** Condition points left on this stack, against {@code spec}'s scale. */
    public static int condition(ItemStack stack, ArmorSpec spec) {
        int max = stack.getMaxDamage();
        if (max <= 0) {
            Integer stored = stack.get(ArmorComponents.CONDITION.get());
            return stored == null ? spec.maxCondition() : Math.min(stored, spec.maxCondition());
        }
        int whole = (max - stack.getDamageValue()) * ArmorUnits.POINTS_PER_DAMAGE;
        int part = remainder(stack);
        return Math.max(0, whole - part);
    }

    /** Condition as a fraction, for a tooltip or a HUD. 1.0 when the item is not armour at all. */
    public static double conditionFraction(ItemStack stack) {
        ArmorSpec spec = ArmorProfiles.specFor(stack);
        if (spec == null || spec.maxCondition() <= 0) {
            return 1.0D;
        }
        return (double) condition(stack, spec) / spec.maxCondition();
    }

    /**
     * Spends {@code points} of condition. Server-side only: this writes a component, and a component
     * written on a worn stack is an equipment-sync packet.
     *
     * @return points actually spent, which is less than asked for when the piece was already dead
     */
    public static int wear(ItemStack stack, int points) {
        if (points <= 0 || stack.isEmpty()) {
            return 0;
        }
        ArmorSpec spec = ArmorProfiles.specFor(stack);
        if (spec == null) {
            return 0;
        }
        int before = condition(stack, spec);
        if (before <= 0) {
            return 0;
        }
        int after = Math.max(0, before - points);
        setCondition(stack, spec, after);
        return before - after;
    }

    /** Puts a piece back to a known condition. Used by repair, by tests, and by nothing else. */
    public static void setCondition(ItemStack stack, ArmorSpec spec, int condition) {
        int clamped = Math.max(0, Math.min(condition, spec.maxCondition()));
        int max = stack.getMaxDamage();
        if (max <= 0) {
            stack.set(ArmorComponents.CONDITION.get(), clamped);
            return;
        }
        int consumed = spec.maxCondition() - clamped;
        int damage = Math.min(max, consumed / ArmorUnits.POINTS_PER_DAMAGE);
        int part = consumed % ArmorUnits.POINTS_PER_DAMAGE;
        stack.setDamageValue(damage);
        if (part == 0) {
            stack.remove(ArmorComponents.WEAR.get());
        } else {
            stack.set(ArmorComponents.WEAR.get(), part);
        }
    }

    private static int remainder(ItemStack stack) {
        Integer part = stack.get(ArmorComponents.WEAR.get());
        if (part == null) {
            return 0;
        }
        return Math.max(0, Math.min(part, ArmorUnits.POINTS_PER_DAMAGE - 1));
    }

    // --- inserts ------------------------------------------------------------------------------

    /** The inserts fitted to this garment, as a mutable copy. Empty when it takes none or has none. */
    public static List<ItemStack> inserts(ItemStack garment) {
        ItemContainerContents contents = garment.get(ArmorComponents.INSERTS.get());
        if (contents == null) {
            return new ArrayList<>(0);
        }
        List<ItemStack> out = new ArrayList<>();
        // Copies, deliberately: the component caches its hash at construction, so a stack taken out of
        // it and then worn in place would leave the garment holding a contents object whose hash no
        // longer matches what it contains.
        contents.stream().forEach(stack -> {
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        });
        return out;
    }

    public static void setInserts(ItemStack garment, List<ItemStack> inserts) {
        if (inserts.isEmpty()) {
            garment.remove(ArmorComponents.INSERTS.get());
        } else {
            garment.set(ArmorComponents.INSERTS.get(), ItemContainerContents.fromItems(inserts));
        }
    }

    /**
     * Whether {@code insert} would go into {@code garment}: the garment takes inserts, it has room,
     * and the insert defends against something the garment is willing to have help with.
     */
    public static boolean canInsert(ItemStack garment, ItemStack insert) {
        ArmorSpec garmentSpec = ArmorProfiles.specFor(garment);
        ArmorSpec insertSpec = ArmorProfiles.specFor(insert);
        if (garmentSpec == null || insertSpec == null || insertSpec.insertSlots() > 0) {
            return false;
        }
        return garmentSpec.acceptsInsert(insertSpec)
                && inserts(garment).size() < garmentSpec.insertSlots();
    }

    // --- assembly -----------------------------------------------------------------------------

    /**
     * The layers on one slot, garment first. Returns null when there is nothing there worth resolving
     * against, so a caller can skip the whole slot rather than resolve against an empty list.
     */
    @Nullable
    public static WornArmor worn(LivingEntity entity, EquipmentSlot slot, double coverage) {
        ItemStack garment = entity.getItemBySlot(slot);
        if (garment.isEmpty()) {
            return null;
        }
        ArmorLayer garmentLayer = layer(garment, coverage);
        if (garmentLayer == null) {
            return null;
        }
        List<ItemStack> inserts = inserts(garment);
        List<ArmorLayer> layers = new ArrayList<>(1 + inserts.size());
        int[] owners = new int[1 + inserts.size()];
        layers.add(garmentLayer);
        owners[0] = -1;
        for (int i = 0; i < inserts.size(); i++) {
            ArmorLayer insertLayer = layer(inserts.get(i), coverage);
            if (insertLayer != null) {
                owners[layers.size()] = i;
                layers.add(insertLayer);
            }
        }
        return new WornArmor(slot, garment, inserts, layers,
                java.util.Arrays.copyOf(owners, layers.size()));
    }
}
