package com.wf.wfballistics.damage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * The type of a hit. A damage class bundles two things: the {@link DamageType} a weapon should deal, and
 * the armour {@linkplain DamageResistanceHandler#category(DamageClass) resistance category} that defends
 * against it. Several classes can map to the same category (LASER and ELECTRIC are both "energy"), so armour
 * can resist a broad family without enumerating every weapon.
 */
public enum DamageClass {

    PHYSICAL(WFDamageTypes.PHYSICAL, DamageResistanceHandler.CATEGORY_PHYSICAL),
    FIRE(WFDamageTypes.FIRE, DamageResistanceHandler.CATEGORY_FIRE),
    EXPLOSIVE(WFDamageTypes.EXPLOSIVE, DamageResistanceHandler.CATEGORY_EXPLOSION),
    ELECTRIC(WFDamageTypes.ELECTRIC, DamageResistanceHandler.CATEGORY_ENERGY),
    LASER(WFDamageTypes.LASER, DamageResistanceHandler.CATEGORY_ENERGY);

    public final ResourceKey<DamageType> type;
    public final String category;

    DamageClass(ResourceKey<DamageType> type, String category) {
        this.type = type;
        this.category = category;
    }

    /** Reverse lookup from a damage type key to the class that produced it, or {@code null} if foreign. */
    public static DamageClass byType(ResourceKey<DamageType> type) {
        for (DamageClass dc : values()) {
            if (dc.type == type) return dc;
        }
        return null;
    }
}
