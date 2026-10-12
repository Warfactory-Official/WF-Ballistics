package com.wf.wflib.armor;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spec per item: authored wins, else derived from vanilla attributes, so armour from mods compiled
 * against neither (GregTech, NTM, ...) stays functional instead of dead weight.
 *
 * <p>{@code DR = defense/25} (vanilla's own small-hit reduction); {@code DT = toughness/2} (toughness
 * halved, since it's a threshold wearing a different hat). Side effect, not tuned for: wear here is
 * absorbed damage, and a mid-tier derived set absorbs about the same quarter-of-hit vanilla already
 * charges its own durability, so hit counts to destruction roughly match vanilla.
 */
public final class ArmorProfiles {

    /** Vanilla's own constant: 25 armour points would be total immunity, and 20 is its cap. */
    private static final double VANILLA_ARMOR_SCALE = 25.0D;
    private static final double TOUGHNESS_TO_DT = 0.5D;

    private static final Map<Item, ArmorSpec> AUTHORED = new ConcurrentHashMap<>();
    private static final Map<Item, ArmorSpec> DERIVED = new ConcurrentHashMap<>();
    /** Negative cache: non-armour items get derivation attempted once, not per hit. */
    private static final Map<Item, Boolean> NOT_ARMOUR = new ConcurrentHashMap<>();

    private ArmorProfiles() {
    }

    /** Registers an authored spec, rebased onto the item's own max-damage. */
    public static void register(Item item, ArmorSpec spec) {
        if (item == null || spec == null) {
            throw new IllegalArgumentException("an armour profile needs both an item and a spec");
        }
        AUTHORED.put(item, spec.withMaxCondition(conditionScale(item, spec.maxCondition())));
        NOT_ARMOUR.remove(item);
        DERIVED.remove(item);
    }

    /** @return the spec for this stack, or null if it is not armour this system knows about */
    @Nullable
    public static ArmorSpec specFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        ArmorSpec authored = AUTHORED.get(item);
        if (authored != null) {
            return rebase(authored, stack);
        }
        if (!ArmorConfig.manageVanillaArmor) {
            return null;
        }
        if (NOT_ARMOUR.containsKey(item)) {
            return null;
        }
        ArmorSpec derived = DERIVED.get(item);
        if (derived == null) {
            derived = derive(item);
            if (derived == null) {
                NOT_ARMOUR.put(item, Boolean.TRUE);
                return null;
            }
            DERIVED.put(item, derived);
        }
        return rebase(derived, stack);
    }

    /** True => this system, not vanilla attributes, is authoritative; see {@link ArmorEvents#onAttributes}. */
    public static boolean managed(ItemStack stack) {
        return specFor(stack) != null;
    }

    /** Test seam: drops cached derived specs so a config change isn't shadowed by a stale cache. */
    public static void clearDerived() {
        DERIVED.clear();
        NOT_ARMOUR.clear();
    }

    @Nullable
    private static ArmorSpec derive(Item item) {
        if (!(item instanceof ArmorItem armor)) {
            return null;
        }
        int defense = armor.getDefense();
        float toughness = armor.getToughness();
        if (defense <= 0 && toughness <= 0.0F) {
            return null;
        }
        double dr = Math.min(defense / VANILLA_ARMOR_SCALE, ArmorUnits.MAX_RESISTANCE);
        double dt = toughness * TOUGHNESS_TO_DT;

        ArmorSpec.Builder builder = ArmorSpec.builder(
                "derived/" + item.getDescriptionId(),
                conditionScale(item, ArmorUnits.POINTS_PER_DAMAGE));
        // Applies equally across every impact-bearing type (vanilla armour is undifferentiated);
        // chemical/radiation excluded so hazmat kit stays meaningful.
        for (ProtectionType type : ProtectionType.VALUES) {
            if (type.takesImpact()) {
                builder.row(type, dt, dr, 0.0D, 0.0D);
            }
        }
        return builder.build();
    }

    /** Rebases to this stack's own max-damage (can differ per stack via components). */
    private static ArmorSpec rebase(ArmorSpec spec, ItemStack stack) {
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return spec;
        }
        int want = max * ArmorUnits.POINTS_PER_DAMAGE;
        return spec.maxCondition() == want ? spec : spec.withMaxCondition(want);
    }

    /** An item's durability in condition points, or {@code fallback} if it has no durability. */
    private static int conditionScale(Item item, int fallback) {
        Integer max = item.components().get(DataComponents.MAX_DAMAGE);
        return max == null || max <= 0 ? fallback : max * ArmorUnits.POINTS_PER_DAMAGE;
    }
}
