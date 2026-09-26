package com.wf.wflib.armor;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which {@link ArmorSpec} an item carries. Authored specs win; anything else that is armour at all
 * gets one derived from its own vanilla numbers.
 *
 * <p>Deriving rather than requiring registration is what kills the content cliff. The pack's armour
 * comes from GregTech, NTM and half a dozen other mods we do not compile against, and a model that
 * only understood armour it had been told about would make every one of those pieces useless. A
 * derived piece behaves near enough to vanilla; an authored one overrides it.
 *
 * <p>Derivation, and why these two lines:
 * <ul>
 *   <li><b>DR is {@code defense / 25}</b>, which is vanilla's own reduction for a hit small enough
 *       that toughness does not enter: an iron chestplate's 6 points become 0.24.</li>
 *   <li><b>DT is {@code toughness / 2}</b>. Toughness in vanilla is the number that stops big hits
 *       from cutting through the percentage, which is a threshold wearing a different hat.</li>
 * </ul>
 * The correspondence goes further than it looks. Our wear is the damage the armour absorbed, and
 * absorbed for a mid-tier set is about a quarter of the hit, which is exactly what vanilla charges
 * its own durability. An iron chestplate derived this way survives about the same number of hits it
 * does in vanilla, without that having been aimed at.
 */
public final class ArmorProfiles {

    /** Vanilla's own constant: 25 armour points would be total immunity, and 20 is its cap. */
    private static final double VANILLA_ARMOR_SCALE = 25.0D;
    /** Toughness is a threshold in all but name, at half weight so a netherite set is not a wall. */
    private static final double TOUGHNESS_TO_DT = 0.5D;

    private static final Map<Item, ArmorSpec> AUTHORED = new ConcurrentHashMap<>();
    private static final Map<Item, ArmorSpec> DERIVED = new ConcurrentHashMap<>();
    /** Items that are not armour at all, so the derivation is attempted once rather than per hit. */
    private static final Map<Item, Boolean> NOT_ARMOUR = new ConcurrentHashMap<>();

    private ArmorProfiles() {
    }

    /**
     * Declares what an item protects against. The spec is rebased onto the item's own durability, so
     * a piece has exactly one number saying how much it can take and it is the one on the item.
     */
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

    /**
     * Whether this system, rather than vanilla's armour attributes, is the authority for this item.
     * Both cannot be: {@link ArmorEvents} strips the attributes off anything that answers true here,
     * because {@code LivingIncomingDamageEvent} fires before vanilla's armour absorb and an item left
     * with both would be counted twice.
     */
    public static boolean managed(ItemStack stack) {
        return specFor(stack) != null;
    }

    /** Test seam: forget every derived spec, so a config change or a test fixture is not shadowed. */
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
        // Every type that arrives as a hit, at the same numbers. Undifferentiated protection is
        // precisely what vanilla armour is, and saying so here is more honest than inventing a
        // ballistic rating for a leather cap. Chemical and radiation get nothing: a helmet is not a
        // respirator, and pretending otherwise would make hazmat kit pointless.
        for (ProtectionType type : ProtectionType.VALUES) {
            if (type.takesImpact()) {
                builder.row(type, dt, dr, 0.0D, 0.0D);
            }
        }
        return builder.build();
    }

    /** The spec at this particular stack's durability, which a component can change per stack. */
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
