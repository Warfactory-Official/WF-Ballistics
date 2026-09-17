package com.wf.wfballistics.damage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HBM's two-number armour model: every defence is a <b>damage threshold</b> (DT, a flat amount subtracted before
 * anything else) plus a <b>damage resistance</b> (DR, a percentage taken off what's left), tracked <em>per damage
 * category</em>.
 */
public final class DamageResistanceHandler {

    public static final String CATEGORY_PHYSICAL = "physical";
    public static final String CATEGORY_FIRE = "fire";
    public static final String CATEGORY_EXPLOSION = "explosion";
    public static final String CATEGORY_ENERGY = "energy";
    public static final String CATEGORY_OTHER = "other";

    private static final ThreadLocal<float[]> PIERCE = ThreadLocal.withInitial(() -> new float[]{0F, 0F});

    private static final Map<Item, ResistanceProfile> ARMOR = new HashMap<>();
    private static final Map<EntityType<?>, ResistanceProfile> INNATE = new HashMap<>();

    private DamageResistanceHandler() {
    }

    // --- piercing context -------------------------------------------------------------------------------

    public static void setup(float pierceDT, float pierceDR) {
        float[] p = PIERCE.get();
        p[0] = pierceDT;
        p[1] = pierceDR;
    }

    public static void reset() {
        float[] p = PIERCE.get();
        p[0] = 0F;
        p[1] = 0F;
    }

    public static float currentPierceDT() {
        return PIERCE.get()[0];
    }

    public static float currentPierceDR() {
        return PIERCE.get()[1];
    }

    // registration

    /**
     * Registers the DT/DR an armour item contributes while worn. Worn pieces' values are summed.
     */
    public static void registerArmor(Item item, ResistanceProfile profile) {
        ARMOR.put(item, profile);
    }

    /**
     * Registers resistance innate to an entity type (e.g. a creeper shrugging off explosions).
     */
    public static void registerEntity(EntityType<?> type, ResistanceProfile profile) {
        INNATE.put(type, profile);
    }

    // queries

    /**
     * @return {@code [totalDT, totalDR]} for {@code entity} against {@code category}; DR clamped to ≤ 1.
     */
    public static float[] getDTDR(LivingEntity entity, String category) {
        return getDTDR(entity, category, null);
    }

    /**
     * As {@link #getDTDR(LivingEntity, String)}, but also consults {@link DynamicResistance} when the source of the
     * hit is known.
     *
     * @param source the hit being resolved, or null if only the category is known
     */
    public static float[] getDTDR(LivingEntity entity, String category, @Nullable DamageSource source) {
        float dt = 0F;
        float dr = 0F;

        if (source != null && entity instanceof DynamicResistance dynamic) {
            float[] live = dynamic.currentDTDR(source);
            dt += live[0];
            dr += live[1];
        }

        ResistanceProfile innate = INNATE.get(entity.getType());
        if (innate != null) {
            Resistance r = innate.get(category);
            dt += r.threshold;
            dr += r.resistance;
        }

        for (ItemStack stack : entity.getArmorSlots()) {
            ResistanceProfile profile = ARMOR.get(stack.getItem());
            if (profile != null) {
                Resistance r = profile.get(category);
                dt += r.threshold;
                dr += r.resistance;
            }
        }

        return new float[]{dt, Math.min(dr, 1F)};
    }

    /**
     * Applies the DT/DR formula. {@code pierceDT}/{@code pierceDR} default to 0 for un-pierced hits.
     */
    public static float calculateDamage(LivingEntity entity, String category, float amount, float pierceDT, float pierceDR) {
        return calculateDamage(entity, category, amount, pierceDT, pierceDR, null);
    }

    public static float calculateDamage(LivingEntity entity, String category, float amount, float pierceDT,
                                        float pierceDR, @Nullable DamageSource source) {
        float[] vals = getDTDR(entity, category, source);
        float dt = vals[0];
        float dr = vals[1];

        dt = Math.max(0F, dt - pierceDT);
        if (dt >= amount) return 0F;
        amount -= dt;

        dr *= Mth.clamp(1F - pierceDR, 0F, 2F);
        return amount * (1F - dr);
    }

    public static String category(DamageClass clazz) {
        return clazz.category;
    }

    /**
     * Picks a resistance category for a damage source: by this mod's classes first, then vanilla tags.
     */
    public static String categoryFor(DamageSource source) {
        Optional<ResourceKey<DamageType>> key = source.typeHolder().unwrapKey();
        if (key.isPresent()) {
            DamageClass dc = DamageClass.byType(key.get());
            if (dc != null) return dc.category;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return CATEGORY_EXPLOSION;
        if (source.is(DamageTypeTags.IS_FIRE)) return CATEGORY_FIRE;
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return CATEGORY_PHYSICAL;
        return CATEGORY_OTHER;
    }

    /**
     * A flat threshold (DT) and a fractional resistance (DR, 0..1) for one category.
     */
    public record Resistance(float threshold, float resistance) {
    }

    /**
     * A per-category resistance table with a fallback used for any category not explicitly listed.
     */
    public static final class ResistanceProfile {
        private final Map<String, Resistance> categories = new HashMap<>();
        private Resistance other = new Resistance(0F, 0F);

        public ResistanceProfile add(String category, float threshold, float resistance) {
            categories.put(category, new Resistance(threshold, resistance));
            return this;
        }

        /**
         * The value used for categories with no explicit entry.
         */
        public ResistanceProfile setOther(float threshold, float resistance) {
            this.other = new Resistance(threshold, resistance);
            return this;
        }

        public Resistance get(String category) {
            return categories.getOrDefault(category, other);
        }
    }
}
