package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidBallistics;
import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Tears the floor up and throws it: a ranged attack that also removes the ground the defender is standing on,
 * which is what turns a fortified position into an open one.
 *
 * <p>The rubble is a real {@link FallingBlockEntity} and lands as a block, so a digger leaves the ground it
 * worked over rearranged.
 */
public class EntityGlyphidDigger extends EntityGlyphid {

    private static final int SLAM_INTERVAL = 120;
    private static final double SLAM_RANGE = 30.0;
    private static final double MIN_SLAM = 3.0;
    /** How far ahead of itself the digger rips up, and over how wide an arc. */
    private static final int SLAM_LENGTH = 6;
    private static final int SLAM_ARC_STEPS = 5;
    private static final double SLAM_ARC = Math.PI / 3.0;
    /** Blocks thrown per slam. A hard cap: the arc is thirty samples, and a warband holds several diggers. */
    private static final int SLAM_MAX_BLOCKS = 8;
    /** Blast resistance a digger can rip out. Above it the block stays and has to be chewed the slow way. */
    private static final float SLAM_RESISTANCE = 100.0F;

    private static final double RUBBLE_SPEED = 1.2;
    private static final double RUBBLE_GRAVITY = 0.04;
    private static final int RUBBLE_LEAD = 30;
    private static final float RUBBLE_DAMAGE_PER_BLOCK = 2.0F;
    private static final int RUBBLE_MAX_DAMAGE = 40;

    private int slamCooldown;
    private @Nullable LivingEntity trackedTarget;
    private double trackedX;
    private double trackedY;
    private double trackedZ;

    public EntityGlyphidDigger(EntityType<? extends EntityGlyphidDigger> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getDigger();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getDigger();
    }


    /**
     * The caste whose whole job is getting through terrain. Ignores the config switch that gates chewing for
     * everything else.
     */
    @Override
    public boolean canDig() {
        return true;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        LivingEntity target = getTarget();
        if (target == null || !isAlive()) {
            trackedTarget = null;
            return;
        }

        if (tickCount % GlyphidBallistics.TRACK_INTERVAL == 0) {
            trackedTarget = target;
            trackedX = target.getX();
            trackedY = target.getY();
            trackedZ = target.getZ();
        }

        if (--slamCooldown <= 0) {
            groundSlam(target);
            slamCooldown = SLAM_INTERVAL;
        }
    }

    private void groundSlam(LivingEntity target) {
        double distance = distanceTo(target);
        if (distance > SLAM_RANGE || distance < MIN_SLAM) {
            return;
        }

        Vec3 aim = GlyphidBallistics.lead(target, trackedX, trackedY, trackedZ,
                trackedTarget == target, RUBBLE_LEAD);
        Vec3 launch = GlyphidBallistics.solve(position(), aim, RUBBLE_SPEED, RUBBLE_GRAVITY, true);
        if (launch == null) {
            return;
        }

        swing(InteractionHand.MAIN_HAND);

        double bearing = Math.atan2(aim.z - getZ(), aim.x - getX());
        int thrown = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int step = 0; step < SLAM_ARC_STEPS && thrown < SLAM_MAX_BLOCKS; step++) {
            double angle = bearing + SLAM_ARC * ((double) step / (SLAM_ARC_STEPS - 1) - 0.5);
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);

            for (int reach = 1; reach <= SLAM_LENGTH && thrown < SLAM_MAX_BLOCKS; reach++) {
                pos.set(Mth.floor(getX() + dx * reach), getBlockY() - 1, Mth.floor(getZ() + dz * reach));
                if (throwRubble(pos, launch)) {
                    thrown++;
                }
            }
        }
    }

    /**
     * @return true if a block was actually torn out
     */
    private boolean throwRubble(BlockPos pos, Vec3 launch) {
        BlockState state = level().getBlockState(pos);
        if (state.isAir()
                || !state.isSolidRender(level(), pos)
                || state.getBlock().getExplosionResistance() >= SLAM_RESISTANCE
                || level().getBlockEntity(pos) != null) {
            return false;
        }

        FallingBlockEntity rubble = FallingBlockEntity.fall(level(), pos, state);
        rubble.setHurtsEntities(RUBBLE_DAMAGE_PER_BLOCK, RUBBLE_MAX_DAMAGE);
        // Named as the swarm's own, so it lands on a defender and not on the diggers standing around it.
        rubble.addTag(RUBBLE_TAG);
        rubble.setDeltaMovement(launch.x + random.nextGaussian() * 0.05,
                launch.y, launch.z + random.nextGaussian() * 0.05);
        return true;
    }

    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.25, 2), 100);
    }
}
