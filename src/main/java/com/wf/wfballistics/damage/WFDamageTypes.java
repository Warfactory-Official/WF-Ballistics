package com.wf.wfballistics.damage;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * {@link ResourceKey}s for the mod's custom {@link DamageType}s. The types themselves are data-driven in
 * {@code data/wfballistics/damage_type/*.json} (1.20's damage sources are registry/datapack objects, not
 * {@code new DamageSource("name")} as in 1.12); these keys are how code refers to them.
 */
public final class WFDamageTypes {

    public static final ResourceKey<DamageType> PHYSICAL = key("physical");
    public static final ResourceKey<DamageType> FIRE = key("fire");
    public static final ResourceKey<DamageType> EXPLOSIVE = key("explosive");
    public static final ResourceKey<DamageType> ELECTRIC = key("electric");
    public static final ResourceKey<DamageType> LASER = key("laser");

    private WFDamageTypes() {
    }

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(WFBallistics.MODID, name));
    }
}
