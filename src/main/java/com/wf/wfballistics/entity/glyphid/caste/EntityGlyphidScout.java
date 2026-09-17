package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.colony.ColonyManager;
import com.wf.wfballistics.colony.ColonyRegistry;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.GlyphidWaypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/** How a colony becomes two colonies. */
public class EntityGlyphidScout extends EntityGlyphid {

    /** How often the scouting logic runs. Site selection reads the heightmap and the colony registry. */
    private static final int SCOUT_INTERVAL = 20;
    /** How far from home a scout will look for a site. */
    private static final int SCOUT_RANGE = 64;
    /** Minimum spacing between nests, or the map fills with colonies all attacking the same base. */
    private static final int NEST_SPACING = 32;
    /**
     * How long a scout sits on a chosen site before committing, so a site it was chased off is not settled anyway.
     */
    private static final int SETTLE_TICKS = 100;
    /** Attempts per selection pass. */
    private static final int SITE_ATTEMPTS = 6;

    private static final int WAYPOINT_RADIUS = 5;
    private static final int WAYPOINT_MAX_AGE = 2400;

    private static final double NOTICE_RANGE = 10.0;
    private static final int VENOM_TICKS = 10 * 20;
    private static final int VENOM_AMPLIFIER = 3;

    private int settling;

    public EntityGlyphidScout(EntityType<? extends EntityGlyphidScout> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getScout();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getScout();
    }

    @Override
    public boolean isScoutType() {
        return true;
    }

    /** A scout that hunts across the map is a scout doing the warband's job. */
    @Override
    public boolean useExtendedTargeting() {
        return false;
    }

    @Override
    public @Nullable LivingEntity findTargetCandidate() {
        if (hasEffect(MobEffects.BLINDNESS)) {
            return null;
        }
        return level().getNearestPlayer(getX(), getY(), getZ(), NOTICE_RANGE, true);
    }

    /** What it lacks in bite it makes up for in venom. */
    @Override
    public boolean doHurtTarget(Entity target) {
        if (!super.doHurtTarget(target)) {
            return false;
        }
        if (target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(MobEffects.POISON, VENOM_TICKS, VENOM_AMPLIFIER));
        }
        return true;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (!(level() instanceof ServerLevel server) || tickCount % SCOUT_INTERVAL != 0) {
            return;
        }

        if (getCurrentTask() != GlyphidTasks.TASK_BUILD_HIVE) {
            // Idle and unattached: go find somewhere to be a colony.
            if (getWaypoint() == null && getTarget() == null) {
                chooseSite(server);
            }
            return;
        }

        if (!isAtDestination()) {
            settling = 0;
            return;
        }

        settling += SCOUT_INTERVAL;
        if (settling >= SETTLE_TICKS) {
            settle(server);
        }
    }

    /** Pick somewhere to found, drop a marker on it and take the escort along. */
    private void chooseSite(ServerLevel server) {
        ColonyRegistry registry = ColonyRegistry.get(server);

        for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
            int x = homeX + random.nextInt(SCOUT_RANGE * 2 + 1) - SCOUT_RANGE;
            int z = homeZ + random.nextInt(SCOUT_RANGE * 2 + 1) - SCOUT_RANGE;
            if (!suitable(server, registry, x, z)) {
                continue;
            }

            int y = server.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

            GlyphidWaypoint site = new GlyphidWaypoint(ModEntities.GLYPHID_WAYPOINT.get(), server);
            site.setWaypointType(GlyphidTasks.TASK_BUILD_HIVE);
            site.radius = WAYPOINT_RADIUS;
            site.maxAge = WAYPOINT_MAX_AGE;
            site.moveTo(x, y, z, 0.0F, 0.0F);
            server.addFreshEntity(site);

            setCurrentTask(GlyphidTasks.TASK_BUILD_HIVE, site);
            communicate(GlyphidTasks.TASK_BUILD_HIVE, site);
            settling = 0;
            return;
        }
    }

    private boolean suitable(ServerLevel server, ColonyRegistry registry, int x, int z) {
        double dx = x - homeX;
        double dz = z - homeZ;
        if (dx * dx + dz * dz < (double) NEST_SPACING * NEST_SPACING) {
            return false;
        }
        if (!server.hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        if (registry.anyWithin(x, z, NEST_SPACING)) {
            return false;
        }

        int y = server.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockState ground = server.getBlockState(new BlockPos(x, y - 1, z));
        return !ground.isAir() && ground.getFluidState().isEmpty() && ground.isSolidRender(server, new BlockPos(x, y - 1, z));
    }

    /** Become the colony. */
    private void settle(ServerLevel server) {
        settling = 0;

        if (ColonyManager.settle(server, getBlockX(), getBlockY(), getBlockZ()) == null) {
            // Too near spawn, or the colony cap is full. Stop trying to found here.
            setCurrentTask(GlyphidTasks.TASK_IDLE, null);
            return;
        }
        discard();
    }

    /** Barely armoured: any hit worth the name strips a plate. */
    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount, 2), 100);
    }
}
