package com.wf.wfballistics.entity.mist;

import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.damage.WFDamageTypes;
import com.wf.wfballistics.entity.MistEntity;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a pool of glyphid acid does to whatever it lands on.
 *
 * <p>Unlike the war gases, this is aimed at a <em>base</em> rather than at a person: it eats blocks. That is
 * the point of a bombing run — a swarm that can only bite things has to reach them, and a colony that can drop
 * corrosion on a wall from above does not.
 *
 * <p>Glyphids are immune to it. They are the ones making it, and a flight that dissolved itself on its own
 * bombing run would be a strange kind of threat.
 */
public class GlyphidAcidMistEffect implements MistEffect {

    /**
     * How often a pool takes a bite out of the blocks under it. Slow on purpose: acid should be something you
     * have time to notice and put out, not an instant hole in the wall.
     */
    private static final int CORROSION_INTERVAL = 30;

    /**
     * Blocks a pool may dissolve per bite, so a wide pool is not proportionally more expensive.
     *
     * <p>Tuned down after measuring: two per twenty ticks cleared every block within the radius over a pool's
     * life, which reads as a demolition charge rather than as acid. One per thirty pits the area instead, so a
     * raid eats its way into a base and lets the swarm through rather than erasing the building.
     */
    private static final int CORROSION_PER_BITE = 1;

    /**
     * Blast resistance a block has to exceed to shrug acid off. Obsidian and reinforced machine casings stand;
     * dirt, stone, wood and most machine blocks do not.
     */
    private static final float CORROSION_LIMIT = 25.0F;

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

        if (living.tickCount % 20 == 0) {
            living.hurt(acidSource(mist), (float) (WFConfig.GLYPHID_ACID_DAMAGE.get() * intensity));
            // Acid goes for the armour first, which is what makes standing in it a losing proposition even
            // for something that can survive the damage.
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty() && stack.isDamageableItem()) {
                    stack.hurtAndBreak(2, living, slot);
                }
            }
        }
    }

    /**
     * Eat the floor. Runs once for the whole pool rather than once per victim, so the cost does not scale with
     * how many things are standing in it.
     */
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
