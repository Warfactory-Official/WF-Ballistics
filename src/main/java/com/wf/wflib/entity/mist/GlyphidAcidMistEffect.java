package com.wf.wflib.entity.mist;

import com.wf.wflib.armor.ArmorExposure;
import com.wf.wflib.armor.ProtectionType;
import com.wf.wflib.config.WFConfig;
import com.wf.wflib.damage.WFDamageTypes;
import com.wf.wflib.entity.MistEntity;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** What a pool of glyphid acid does to whatever it lands on. */
public class GlyphidAcidMistEffect implements MistEffect {

    /** How often a pool takes a bite out of the blocks under it. */
    private static final int CORROSION_INTERVAL = 30;

    /** Blocks a pool may dissolve per bite, so a wide pool is not proportionally more expensive. */
    private static final int CORROSION_PER_BITE = 1;

    /** Blast resistance a block has to exceed to shrug acid off. */
    private static final float CORROSION_LIMIT = 25.0F;

    /** What acid costs armour at full concentration; see the same constant on the mustard gas effect. */
    private static final double EXPOSURE_TIER = 250.0D;

    @Override
    public void affect(MistEntity mist, Entity target, double intensity) {
        // The colony is immune to its own chemistry.
        if (target instanceof EntityGlyphid) {
            return;
        }
        if (!(target instanceof LivingEntity living) || living.isInvulnerable()) {
            return;
        }

        int duration = (int) (60 * intensity) + 20;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 1));
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, 0));

        // Acid is corrosive whether or not it is currently doing damage, so the contact is declared
        // every tick the pool is on the victim and the armour system decides what it costs.
        ArmorExposure.contact(living, ProtectionType.CHEMICAL, EXPOSURE_TIER * intensity);
        if (living.tickCount % 20 == 0) {
            living.hurt(acidSource(mist), (float) (WFConfig.GLYPHID_ACID_DAMAGE.get() * intensity));
        }
    }

    /** Eat the floor. */
    @Override
    public void areaTick(MistEntity mist, double intensity) {
        if (!WFConfig.GLYPHID_ACID_CORRODES.get() || mist.tickCount % CORROSION_INTERVAL != 0) {
            return;
        }
        var level = mist.level();
        double radius = Math.max(1.0, mist.getRadius());

        for (int i = 0; i < CORROSION_PER_BITE; i++) {
            int x = (int) Math.floor(mist.getX() + (level.random.nextDouble() * 2 - 1) * radius);
            int z = (int) Math.floor(mist.getZ() + (level.random.nextDouble() * 2 - 1) * radius);
            int y = (int) Math.floor(mist.getY()) - level.random.nextInt(2);

            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (state.getBlock().getExplosionResistance() > CORROSION_LIMIT) {
                continue;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static DamageSource acidSource(MistEntity mist) {
        return new DamageSource(mist.level().registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(WFDamageTypes.ACID));
    }

    @Override
    public int color(MistEntity mist) {
        return 0x8CD836; // bright bile green
    }
}
