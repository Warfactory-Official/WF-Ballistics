package com.wf.wflib.entity.glyphid.caste;

import com.wf.wflib.entity.MistEntity;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidStats;
import com.wf.wflib.fluid.WFFluids;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** The siege caste: plants itself and breathes acid. */
public class EntityGlyphidBehemoth extends EntityGlyphid {

    private static final int BREATH_COOLDOWN = 120;
    private static final int BREATH_TICKS = 120;
    private static final double BREATH_RANGE = 20.0;
    /** Ticks between puffs. */
    private static final int BREATH_PUFF_INTERVAL = 10;
    private static final int BREATH_PUFF_DURATION = 60;
    private static final float BREATH_PUFF_RADIUS = 2.5F;
    private static final float BREATH_PUFF_HEIGHT = 2.5F;
    /** Where along the line to the target the puffs land. */
    private static final double BREATH_NEAR = 2.0;
    private static final double BREATH_FAR = 9.0;

    private static final float DEATH_MIST_RADIUS = 10.0F;
    private static final float DEATH_MIST_HEIGHT = 4.0F;
    private static final int DEATH_MIST_DURATION = 120;

    private int breathCooldown = BREATH_COOLDOWN;
    private int breathTicks;

    public EntityGlyphidBehemoth(EntityType<? extends EntityGlyphidBehemoth> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getBehemoth();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getBehemoth();
    }

    /**
     * Pins the facing across the whole tick, because the look control runs inside {@code super.tick()} and would
     * otherwise swing the head onto the target between breaths.
     */
    @Override
    public void tick() {
        boolean pinned = !level().isClientSide && breathTicks > 0;
        float heldYaw = getYRot();
        super.tick();
        if (pinned) {
            setYRot(heldYaw);
            yBodyRot = heldYaw;
            yHeadRot = heldYaw;
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        LivingEntity target = getTarget();
        if (target == null) {
            breathCooldown = BREATH_COOLDOWN;
            breathTicks = 0;
            return;
        }

        if (breathTicks > 0) {
            if (!swinging) {
                swing(InteractionHand.MAIN_HAND);
            }
            addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 2 * 20, 6));
            if (breathTicks % BREATH_PUFF_INTERVAL == 0) {
                spray(target);
            }
            breathTicks--;
        } else if (--breathCooldown <= 0) {
            breathTicks = BREATH_TICKS;
            breathCooldown = BREATH_COOLDOWN;
        }
    }

    private void spray(LivingEntity target) {
        if (distanceTo(target) > BREATH_RANGE) {
            return;
        }

        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return;
        }

        double reach = BREATH_NEAR + random.nextDouble() * (BREATH_FAR - BREATH_NEAR);
        double x = getX() + dx / horizontal * reach;
        double z = getZ() + dz / horizontal * reach;

        MistEntity.spawn(level(), WFFluids.GLYPHID_ACID.get(), x, getY(), z,
                BREATH_PUFF_RADIUS, BREATH_PUFF_HEIGHT, BREATH_PUFF_DURATION);
    }

    /** A dead behemoth is still a hazard: whatever killed it is standing in what it was full of. */
    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) {
            MistEntity.spawn(level(), WFFluids.GLYPHID_ACID.get(), getX(), getY(), getZ(),
                    DEATH_MIST_RADIUS, DEATH_MIST_HEIGHT, DEATH_MIST_DURATION);
        }
    }

    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.15, 2), 100);
    }

    @Override
    public int swingDuration() {
        return 100;
    }
}
