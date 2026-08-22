package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.config.WFConfig;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A marker a glyphid colony leaves in the world: "go here, and when you arrive, do this".
 *
 * <p>An entity rather than a record because it has to be found by whichever bugs happen to walk past it,
 * without any of them holding a reference first. It ticks rarely, never moves, and takes no damage.
 *
 * <p>Note for later phases: one entity per waypoint is affordable at colony scale and will not be at swarm
 * scale. This is the faithful port; the carrier work replaces it with data held by the scheduler.
 */
public class GlyphidWaypoint extends Entity {

    private static final EntityDataAccessor<Integer> WAYPOINT_TYPE =
            SynchedEntityData.defineId(GlyphidWaypoint.class, EntityDataSerializers.INT);

    public int maxAge = 2400;
    public int radius = 3;
    /**
     * A high-priority waypoint interrupts whatever the bug was chasing, rather than queuing behind it.
     */
    public boolean highPriority = false;

    protected @Nullable GlyphidWaypoint additional;
    private boolean hasSpawned = false;

    public GlyphidWaypoint(EntityType<? extends GlyphidWaypoint> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(WAYPOINT_TYPE, GlyphidTasks.TASK_IDLE);
    }

    public int getWaypointType() {
        return entityData.get(WAYPOINT_TYPE);
    }

    public void setWaypointType(int waypointType) {
        entityData.set(WAYPOINT_TYPE, waypointType);
    }

    public void setHighPriority() {
        highPriority = true;
    }

    /**
     * Chain a second waypoint to be dropped once this one is reached, which is how a retreat lays a trail
     * home and back.
     */
    public void setAdditionalWaypoint(@Nullable GlyphidWaypoint waypoint) {
        additional = waypoint;
    }

    public int getColor() {
        int type = getWaypointType();
        if (type == GlyphidTasks.TASK_RETREAT_FOR_REINFORCEMENTS) return 0x5FA6E8;
        if (type == GlyphidTasks.TASK_BUILD_HIVE || type == GlyphidTasks.TASK_INITIATE_RETREAT) return 0x127766;
        return 0x566573;
    }

    @Override
    public void tick() {
        super.tick();

        if (tickCount >= maxAge) {
            discard();
            return;
        }

        AABB bb = new AABB(getX(), getY(), getZ(), getX(), getY(), getZ()).inflate(radius);

        if (!level().isClientSide()) {
            if (tickCount % 40 == 0) {
                claim(bb);
            }
        } else if (WFConfig.GLYPHID_WAYPOINT_DEBUG.get()) {
            double x = bb.minX + (random.nextDouble() - 0.5) * (bb.maxX - bb.minX);
            double y = bb.minY + random.nextDouble() * (bb.maxY - bb.minY);
            double z = bb.minZ + (random.nextDouble() - 0.5) * (bb.maxZ - bb.minZ);
            level().addParticle(new DustParticleOptions(Vec3.fromRGB24(getColor()).toVector3f(), 1.0F),
                    x, y, z, 0D, 0D, 0D);
        }
    }

    /**
     * Hand this waypoint's order to any glyphid standing in it, then retire. A hive-building waypoint is
     * the exception: it stays until a scout claims it, so the workers it also passes orders to do not
     * consume it first.
     */
    private void claim(AABB bb) {
        List<Entity> targets = level().getEntities(this, bb);

        for (Entity e : targets) {
            if (!(e instanceof EntityGlyphid bug)) {
                continue;
            }

            if (additional != null && !hasSpawned) {
                level().addFreshEntity(additional);
                hasSpawned = true;
            }

            boolean exceptions = bug.getWaypoint() != this || bug.isScoutType() || bug.isNuclearType();
            if (!exceptions) {
                bug.setCurrentTask(getWaypointType(), additional);
            }

            if (getWaypointType() == GlyphidTasks.TASK_BUILD_HIVE) {
                if (bug.isScoutType()) discard();
            } else {
                discard();
                return;
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        setWaypointType(compound.getInt("type"));
        if (compound.contains("radius")) radius = compound.getInt("radius");
        if (compound.contains("maxAge")) maxAge = compound.getInt("maxAge");
        highPriority = compound.getBoolean("highPriority");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        compound.putInt("type", getWaypointType());
        compound.putInt("radius", radius);
        compound.putInt("maxAge", maxAge);
        compound.putBoolean("highPriority", highPriority);
    }
}
