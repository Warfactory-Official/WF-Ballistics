package com.wf.wflib.entity.glyphid.caste;

import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.EntityGlyphidBomb;
import com.wf.wflib.entity.glyphid.GlyphidBallistics;
import com.wf.wflib.entity.glyphid.GlyphidStats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Artillery. */
public class EntityGlyphidBombardier extends EntityGlyphid {

    /** Ticks between volleys. Offset by one so the volley never lands on the same tick as the tracking sample. */
    protected static final int VOLLEY_INTERVAL = 60;
    /** Beyond this, shoot over rather than at: the high solution clears cover, the flat one is faster. */
    protected static final double LOFT_RANGE = 20.0;
    /**
     * Do not bother below this: a target this close is the melee goal's problem, and the arc would drop the volley
     * on the bombardier's own back.
     */
    protected static final double MIN_RANGE = 3.0;
    /** How far ahead of a moving target to aim, per solution. */
    protected static final int LEAD_FLAT = 20;
    protected static final int LEAD_LOFT = 60;

    protected @Nullable LivingEntity trackedTarget;
    protected double trackedX;
    protected double trackedY;
    protected double trackedZ;

    public EntityGlyphidBombardier(EntityType<? extends EntityGlyphidBombardier> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getBombardier();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getBombardier();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        LivingEntity target = getTarget();
        if (target == null) {
            trackedTarget = null;
            return;
        }

        if (tickCount % GlyphidBallistics.TRACK_INTERVAL == 0) {
            trackedTarget = target;
            trackedX = target.getX();
            trackedY = target.getY();
            trackedZ = target.getZ();
        }

        if (tickCount % VOLLEY_INTERVAL == 1) {
            volley(target);
        }
    }

    /**
     * Solve once, then throw the whole salvo down the same heading with a widening spread, so a volley covers an
     * area rather than stacking every bomb on one block.
     */
    protected void volley(LivingEntity target) {
        boolean loft = distanceTo(target) > LOFT_RANGE;
        Vec3 muzzle = new Vec3(getX(), getY() + 1.0, getZ());
        Vec3 aim = GlyphidBallistics.lead(target, trackedX, trackedY, trackedZ,
                trackedTarget == target, loft ? LEAD_LOFT : LEAD_FLAT);

        if (muzzle.distanceTo(aim) < MIN_RANGE) {
            return;
        }

        double speed = muzzleSpeed();
        Vec3 launch = GlyphidBallistics.solve(muzzle, aim, speed, EntityGlyphidBomb.GRAVITY, loft);
        if (launch == null) {
            return;
        }

        for (int i = 0; i < bombCount(); i++) {
            EntityGlyphidBomb bomb = new EntityGlyphidBomb(level(), this);
            bomb.setPos(muzzle.x, muzzle.y, muzzle.z);
            bomb.setExplosive(dropsExplosives());
            bomb.shoot(launch.x, launch.y, launch.z, (float) speed, i * spread());
            level().addFreshEntity(bomb);
        }
        swing(InteractionHand.MAIN_HAND);
    }

    public int bombCount() {
        return 5;
    }

    /** Inaccuracy multiplier, applied per bomb in the salvo so the first is aimed and the last is scattered. */
    public float spread() {
        return 1.0F;
    }

    public double muzzleSpeed() {
        return 1.0;
    }
}
