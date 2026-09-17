package com.wf.wfballistics.damage;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/** {@link ResourceKey}s for the mod's custom {@link DamageType}s. */
public final class WFDamageTypes {

    public static final ResourceKey<DamageType> PHYSICAL = key("physical");
    public static final ResourceKey<DamageType> FIRE = key("fire");
    public static final ResourceKey<DamageType> EXPLOSIVE = key("explosive");
    public static final ResourceKey<DamageType> ELECTRIC = key("electric");
    public static final ResourceKey<DamageType> LASER = key("laser");
    public static final ResourceKey<DamageType> ACID = key("acid");
    /** War gases. Bypasses armour and, by tag, the invulnerability window (see {@link WFDamage}). */
    public static final ResourceKey<DamageType> GAS = key("gas");

    private WFDamageTypes() {
    }

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, name));
    }
}
