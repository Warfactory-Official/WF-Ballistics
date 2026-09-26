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
 * Wound axis to protection axis, the many-to-one projection. Deliberately outside the pure core: the
 * core is free of Minecraft types and must stay that way, and this is nothing but Minecraft types.
 *
 * <p>Returning null is a real answer and not a failure. Starvation, the void and the world border
 * have no protection type because no material answers them, which is the axis split doing its job.
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

        // Tags next, for damage types this mod has never heard of.
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
            // Left unprotectable on purpose. Mapping it to IMPACT is defensible (jump boots), but it
            // invites an armour set that makes falling free, and this is the wrong place to decide that.
            return null;
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return ProtectionType.KINETIC;
        }

        // Anything still unaccounted for is somebody else's damage type. A mod that says its damage
        // ignores armour is taken at its word; otherwise a hit is a hit, and blunt is the safe reading,
        // because treating unknown damage as unprotectable would quietly make armour useless against
        // every modded mob in the pack.
        return source.is(DamageTypeTags.BYPASSES_ARMOR) ? null : ProtectionType.IMPACT;
    }

    @Nullable
    private static ProtectionType byKey(ResourceKey<DamageType> key, DamageSource source) {
        // This mod's own, first: they are the ones with a deliberate answer.
        if (key == WFDamageTypes.PHYSICAL) {
            return ProtectionType.KINETIC;
        }
        if (key == WFDamageTypes.EXPLOSIVE) {
            return ProtectionType.BLAST;
        }
        if (key == WFDamageTypes.ELECTRIC) {
            return ProtectionType.ELECTRIC;
        }
        // A laser burns. A reflective coat and a fire-resistant weave are the same answer, and the
        // thing rubber insulation does against a taser is not it, which is why ENERGY had to split.
        if (key == WFDamageTypes.FIRE || key == WFDamageTypes.LASER) {
            return ProtectionType.THERMAL;
        }
        // Gas is tagged bypasses_armor because vanilla armour has no business stopping it. A sealed
        // suit does, so the explicit mapping is checked before the tag.
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

    /**
     * A knife and a bayonet make different wounds and are defeated by the same weave, which is the
     * clearest case for the two axes; a fist and a club are not, and get padding instead.
     */
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
