package com.wf.wflib.armor;

import com.wf.wflib.damage.WFDamageTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * {@code DamageSource} -> {@link ProtectionType}, many-to-one. Kept outside the pure core (core stays
 * free of Minecraft types). null is a valid answer: starvation, void, world border have no material
 * that could ever answer them.
 */
public final class ArmorProjection {

    private ArmorProjection() {
    }

    /**
     * @return what material would answer this hit, or null if nothing can
     */
    @Nullable
    public static ProtectionType typeFor(DamageSource source) {
        if (source == null) {
            return null;
        }
        Optional<ResourceKey<DamageType>> key = source.typeHolder().unwrapKey();
        if (key.isPresent()) {
            ProtectionType named = byKey(key.get(), source);
            if (named != null) {
                return named;
            }
        }

        // Fallback for damage types without a named key.
        if (source.is(DamageTypeTags.IS_LIGHTNING)) {
            return ProtectionType.ELECTRIC;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return ProtectionType.BLAST;
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return ProtectionType.THERMAL;
        }
        if (source.is(DamageTypeTags.IS_FALL)) {
            // Deliberately unprotectable: IMPACT mapping would let armour alone negate fall damage.
            return null;
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return ProtectionType.KINETIC;
        }

        // Unknown type: default to IMPACT rather than null, else armour would quietly do nothing
        // against unrecognized modded damage; BYPASSES_ARMOR is still honored.
        return source.is(DamageTypeTags.BYPASSES_ARMOR) ? null : ProtectionType.IMPACT;
    }

    @Nullable
    private static ProtectionType byKey(ResourceKey<DamageType> key, DamageSource source) {
        if (key == WFDamageTypes.PHYSICAL) {
            return ProtectionType.KINETIC;
        }
        if (key == WFDamageTypes.EXPLOSIVE) {
            return ProtectionType.BLAST;
        }
        if (key == WFDamageTypes.ELECTRIC) {
            return ProtectionType.ELECTRIC;
        }
        // Laser -> THERMAL not ELECTRIC: same countermeasure as fire, unlike actual electric shock.
        if (key == WFDamageTypes.FIRE || key == WFDamageTypes.LASER) {
            return ProtectionType.THERMAL;
        }
        // Gas/acid tagged BYPASSES_ARMOR for vanilla armour, but a sealed suit should defend; hence
        // explicit CHEMICAL mapping here, checked before the tag in typeFor.
        if (key == WFDamageTypes.GAS || key == WFDamageTypes.ACID) {
            return ProtectionType.CHEMICAL;
        }

        if (key == DamageTypes.ARROW || key == DamageTypes.TRIDENT || key == DamageTypes.SPIT
                || key == DamageTypes.MOB_PROJECTILE || key == DamageTypes.STING
                || key == DamageTypes.CACTUS || key == DamageTypes.SWEET_BERRY_BUSH
                || key == DamageTypes.STALAGMITE || key == DamageTypes.THORNS) {
            return ProtectionType.SHARP;
        }
        if (key == DamageTypes.PLAYER_ATTACK || key == DamageTypes.MOB_ATTACK
                || key == DamageTypes.MOB_ATTACK_NO_AGGRO) {
            return meleeType(source);
        }
        if (key == DamageTypes.FALLING_BLOCK || key == DamageTypes.FALLING_ANVIL
                || key == DamageTypes.FALLING_STALACTITE || key == DamageTypes.FLY_INTO_WALL
                || key == DamageTypes.WIND_CHARGE || key == DamageTypes.SONIC_BOOM) {
            return ProtectionType.IMPACT;
        }
        if (key == DamageTypes.FIREWORKS) {
            return ProtectionType.BLAST;
        }
        if (key == DamageTypes.DRAGON_BREATH) {
            return ProtectionType.CHEMICAL;
        }
        return null;
    }

    /** Edged weapons (sword/axe/pickaxe) -> SHARP; blunt (fist, club) -> IMPACT. */
    private static ProtectionType meleeType(DamageSource source) {
        if (source.getDirectEntity() instanceof LivingEntity attacker) {
            ItemStack weapon = attacker.getMainHandItem();
            if (weapon.is(ItemTags.SWORDS) || weapon.is(ItemTags.AXES) || weapon.is(ItemTags.PICKAXES)) {
                return ProtectionType.SHARP;
            }
        }
        return ProtectionType.IMPACT;
    }
}
