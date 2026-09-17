package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.EntityGlyphidBomb;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Drops ordnance on whatever a flying glyphid happens to be over, which is what makes wings an attack rather than a
 * way of arriving: a flight can corrode a base from above without landing.
 */
public class GlyphidBombGoal extends Goal {

    /** Vertical clearance needed before a bomb is worth releasing. Below this it would land at its own feet. */
    private static final double MIN_DROP_HEIGHT = 4.0;

    /** How far from the target a glyphid starts its run. */
    private static final double RUN_IN_RANGE = 24.0;

    private final EntityGlyphid glyphid;
    private int cooldown;

    public GlyphidBombGoal(EntityGlyphid glyphid) {
        this.glyphid = glyphid;
        // No MOVE flag: bombing happens on the way past, not instead of flying.
        setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        return WFConfig.GLYPHID_BOMBING.get()
                && glyphid.isAirborne()
                && glyphid.bombs() > 0
                && overSomethingWorthHitting();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (cooldown-- > 0) {
            return;
        }
        cooldown = WFConfig.GLYPHID_BOMB_INTERVAL.get();
        release();
    }

    /**
     * @return whether there is a target below worth spending a bomb on, and enough air under the glyphid to
     *      drop it through.
     */
    private boolean overSomethingWorthHitting() {
        double clearance = glyphid.getY() - groundBelow();
        if (clearance < MIN_DROP_HEIGHT) {
            return false;
        }
        LivingEntity target = glyphid.getTarget();
        if (target != null && target.distanceToSqr(glyphid) < RUN_IN_RANGE * RUN_IN_RANGE) {
            return true;
        }
        // Otherwise bomb the place it was sent to, which is the base that provoked the colony.
        double dx = glyphid.taskX - glyphid.getX();
        double dz = glyphid.taskZ - glyphid.getZ();
        return dx * dx + dz * dz < RUN_IN_RANGE * RUN_IN_RANGE;
    }

    private void release() {
        if (!(glyphid.level() instanceof ServerLevel)) {
            return;
        }
        EntityGlyphidBomb bomb = new EntityGlyphidBomb(glyphid.level(), glyphid);
        bomb.setExplosive(glyphid.dropsExplosives());
        bomb.setPos(glyphid.getX(), glyphid.getY() - 0.4, glyphid.getZ());

        Vec3 motion = glyphid.getDeltaMovement();
        bomb.setDeltaMovement(motion.x, Math.min(0.0, motion.y) - 0.1, motion.z);

        glyphid.level().addFreshEntity(bomb);
        glyphid.spendBomb();
        glyphid.playSound(SoundEvents.SLIME_SQUISH, 1.0F, 0.7F);
    }

    private int groundBelow() {
        int x = glyphid.getBlockX();
        int z = glyphid.getBlockZ();
        if (!glyphid.level().hasChunk(x >> 4, z >> 4)) {
            return glyphid.getBlockY();
        }
        return glyphid.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
