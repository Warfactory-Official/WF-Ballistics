package com.wf.wflib.damage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

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

    /** Null: {@code type} is not one of ours (foreign). */
    public static DamageClass byType(ResourceKey<DamageType> type) {
        for (DamageClass dc : values()) {
            if (dc.type == type) return dc;
        }
        return null;
    }
}
