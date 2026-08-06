package com.wf.wfballistics.warhead;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.fx.ExplosionCreator;
import com.wf.wfballistics.swarm.SwarmManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Recursive fragmentation warhead: instead of exploding, a missile splits into a spread of smaller
 * <em>missilelets</em> that each fly their own terminal dive — and each of <em>those</em> splits again,
 * until the leaf generation finally detonates. A "double recursive" cluster: one missile becomes a swarm.
 *
 * <p>Splitting is driven by the missile's existing airburst fuze ({@link MissileEntity} detonates in the
 * air once it dives within {@link #splitAltitude} of its target). On detonation this warhead checks the
 * missile's {@link MissileEntity#getSplitDepth() split depth}: while it is positive it spawns the next
 * generation; at zero the leaf missilelet does a real (small) blast.
 *
 * <p>Every missilelet in one launch shares a {@link MissileEntity#getSwarmId() swarm id}, so the family
 * never collides with itself (see {@link MissileEntity#canHitEntity}) — otherwise the cluster would
 * detonate itself the instant it spawned, now that missile-vs-missile collision is live.
 */
public final class RecursiveFrag {

    /**
     * Registered warhead id (also selectable from the dispenser GUI).
     */
    public static final ResourceLocation ID = new ResourceLocation(WFBallistics.MODID, "recursive_frag");
    // DEBUG: logs the split geometry so it's clear whether the child aim points land on ground or in mid-air.
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Default generations for a GUI-launched recursive missile: splits, those split, then a blast.
     */
    public static final int DEFAULT_DEPTH = 2;
    /**
     * Missilelets spawned per split, clamped from the missile's fragment count.
     */
    private static final int MIN_CHILDREN = 2;
    private static final int MAX_CHILDREN = 5;
    /**
     * Small submunition airframe the missilelets fly as — a scaled-down cluster warhead, distinct from (and
     * smaller than) the parent {@code cluster} model, since each missilelet only carries a fraction of the
     * original payload.
     */
    private static final ResourceLocation CHILD_MODEL = MissileModels.rl("cluster_part");
    /**
     * Missilelet cruise/dive speed (blocks/tick) and the outward burst speed at the moment of splitting.
     */
    private static final double CHILD_SPEED = 2.0;
    private static final double SPREAD_SPEED = 3.0;
    // Outward-vs-downward bias of the split burst: higher = the missilelets fan out flatter before diving.
    private static final double SPREAD_LATERAL = 1.3;
    private static final float CHILD_HEALTH = 10.0f;
    /**
     * Leaf (final generation) blast radius — kept small; a full cluster is many of these.
     */
    private static final float LEAF_BLAST_RADIUS = 4.0f;

    private RecursiveFrag() {
    }

    /**
     * Airburst altitude (blocks above the target's Y) at which a missile of the given remaining split depth
     * fuzes and splits. Leaves (depth 0) use 0 = contact detonation. Kept small so the whole cascade fits
     * inside a normal terminal dive.
     */
    public static float splitAltitude(int depth) {
        return depth > 0 ? depth * 6.0f + 4.0f : 0.0f; // depth2 -> 16, depth1 -> 10, leaf -> 0 (contact)
    }

    /**
     * Horizontal radius the child aimpoints are scattered over (wider for the earlier, higher splits).
     */
    private static double scatterRadius(int childDepth) {
        return (childDepth + 1) * 12.0; // depth1 children ~24, depth0 grandchildren ~12
    }

    /**
     * Warhead entry point (registered as {@link #ID}).
     */
    public static void detonate(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        if (source instanceof MissileEntity missile && missile.getSplitDepth() > 0) {
            split(missile, pos);
        } else {
            leafBlast(level, pos, source.igniterFactionId());
        }
    }

    private static void split(MissileEntity parent, Vec3 pos) {
        Level level = parent.level();
        RandomSource rng = level.random;

        int childDepth = parent.getSplitDepth() - 1;
        int count = Mth.clamp(parent.getFragmentCount(), MIN_CHILDREN, MAX_CHILDREN);
        float childOffset = splitAltitude(childDepth);
        double radius = scatterRadius(childDepth);
        Vec3 target = parent.getTarget();

        // One shared swarm id for the whole family, generated at the first split and inherited thereafter,
        // so the members never collide with each other and steer apart in flight (see SwarmManager).
        long swarm = parent.getSwarmId();
        if (swarm == 0L) {
            swarm = SwarmManager.newId(level);
        }
        // Inherit the launcher's control id so the whole cluster (and sibling launches) stay friendly.
        UUID control = parent.getControlId();

        // All missiles share a single entity type; the model is chosen per-missile (here: the micro frame).
        EntityType<? extends Projectile> type = ModEntities.STEALTH_MISSILE.get();

        // Deconflict the aimpoints: space them evenly around a ring (random overall rotation + a little
        // per-child jitter) so the swarm carpets the target area instead of clumping.
        double baseAngle = rng.nextDouble() * Math.PI * 2.0;
        double stepAngle = (Math.PI * 2.0) / count;

        int groundUnderParentTarget = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(target.x), Mth.floor(target.z));
        LOGGER.info("[SPLIT parent={} swarm={} depth={} -> {} children] splitPos=({},{},{}) parentTgt=({},{},{}) "
                        + "childOffset={} scatterR={} groundUnderTgt={} tgtAGL={}",
                parent.getId(), swarm, parent.getSplitDepth(), count,
                fmt(pos.x), fmt(pos.y), fmt(pos.z), fmt(target.x), fmt(target.y), fmt(target.z),
                fmt(childOffset), fmt(radius), groundUnderParentTarget, fmt(target.y - groundUnderParentTarget));

        for (int i = 0; i < count; i++) {
            double angle = baseAngle + i * stepAngle + (rng.nextDouble() - 0.5) * stepAngle * 0.4;
            double r = radius * (0.7 + 0.3 * rng.nextDouble());
            double hx = Math.cos(angle);
            double hz = Math.sin(angle);
            double childX = target.x + hx * r;
            double childZ = target.z + hz * r;
            // Aim at the actual surface beneath each scattered point, not the parent's target altitude. Over
            // uneven terrain a fixed Y leaves the aimpoint floating in mid-air, and the terminal dive — which
            // pursues a carrot capped at the target and so can't be led below it — then orbits that empty point
            // forever instead of coming down (confirmed by the tgtAGL/vy logs). Snapping to the ground makes
            // every missilelet dive onto real ground.
            int childGroundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(childX), Mth.floor(childZ));
            Vec3 childTarget = new Vec3(childX, childGroundY, childZ);

            MissileEntity child = MissileEntity.builder(type, level)
                    .model(CHILD_MODEL)
                    .target(childTarget)
                    .splitDepth(childDepth)
                    .swarmId(swarm)
                    .controlId(control)
                    .detonation(ID)
                    .startInAttack()          // instant attack mode
                    .explosionOffset(childOffset)
                    .fragmentCount(count)
                    .cruiseSpeed(CHILD_SPEED)
                    .terrainFollow(6.0)
                    .health(CHILD_HEALTH)
                    .build();

            child.moveTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
            // Burst outward toward its own sector (down-biased) so the missilelets separate immediately,
            // toward their assigned aimpoints rather than in a random cone.
            Vec3 burst = new Vec3(hx * SPREAD_LATERAL, -1.0, hz * SPREAD_LATERAL).normalize().scale(SPREAD_SPEED);
            child.setDeltaMovement(burst);
            level.addFreshEntity(child);

            LOGGER.info("  child={} depth={} tgt=({},{},{}) groundUnderTgt={} tgtAGL={} (aimpoint {})",
                    child.getId(), childDepth, fmt(childTarget.x), fmt(childTarget.y), fmt(childTarget.z),
                    childGroundY, fmt(childTarget.y - childGroundY),
                    childTarget.y - childGroundY > 1.0 ? "IN AIR" : "on ground");
        }

        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private static void leafBlast(Level level, Vec3 pos, java.util.UUID igniterFaction) {
        new ExplosionAEF(level, pos.x, pos.y, pos.z, LEAF_BLAST_RADIUS)
                .makeStandard()
                .igniterFaction(igniterFaction)
                .explode();
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    }
}
