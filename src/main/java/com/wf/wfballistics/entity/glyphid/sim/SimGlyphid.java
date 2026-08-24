package com.wf.wfballistics.entity.glyphid.sim;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidBrain;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidCarrier;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidMind;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidPlan;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidSnapshot;
import com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowField;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One glyphid, without an entity: §2's T1 tier. A glyphid nobody is standing near needs no hitbox, collision
 * sweep, navigator, attribute map or entity tick slot — 53% of what a marching swarm spent.
 *
 * <p>What is left is a record that walks, running the same {@link GlyphidBrain} against the same
 * {@link GlyphidMind} a body does and carrying the decision out by moving a double.
 *
 * <p>Two navigation models. Inside a {@link GlyphidFlowField} it walks the field, which only crosses columns
 * a glyphid could stand in, so it needs no collision. Outside one it walks straight over the surface height —
 * a lie about terrain told only where nobody can see it, and one that ends the moment it stops progressing.
 *
 * <p>It deliberately cannot fight, swim, fly, dig or found a nest. Each of those promotes it back to an
 * entity rather than being reimplemented here; see {@link SimGlyphidManager}.
 */
public final class SimGlyphid implements GlyphidCarrier {

    /**
     * Blocks per tick per point of {@code MOVEMENT_SPEED}. Calibrated against the entity tier, not derived:
     * vanilla's terminal speed for a grunt is 0.375, but path following slows it to a measured 0.130 over a
     * 160-block march. Setting this from the physics made the sim tier 56% faster and the swarm arrive in two
     * waves. {@code swarmbench tiers} reports both tiers off one run, which is what would catch it drifting.
     */
    private static final double SPEED_PER_ATTRIBUTE = 0.55;
    /** Height gained or lost per tick. The field connects columns eight blocks apart; snapping teleports. */
    private static final double CLIMB_RATE = 0.4;
    /** How far the ground may be re-read from. Beyond it the record keeps the height it had. */
    private static final double MAX_FALL = 4.0;

    /**
     * Negative, and unique per level. Entity ids are positive, so a client can key one store by id and tell
     * the tiers apart without a flag; the stagger arithmetic is a {@code floorMod} and does not mind.
     */
    public final int id;
    public final GlyphidCaste caste;

    public double x;
    public double y;
    public double z;
    /** Where it was last tick, so the client can interpolate and the walk cycle has something to run off. */
    public double prevX;
    public double prevY;
    public double prevZ;
    public float yRot;

    public float health;
    public byte armor = EntityGlyphid.FULL_ARMOR;
    public byte subtype = EntityGlyphid.TYPE_NORMAL;
    public boolean persistent;
    /**
     * Carried across the boundary: a garrison bug that lost this on a round trip would stop being counted,
     * and its colony would regrow the population still standing in front of it.
     */
    public boolean garrison;

    /** Its own age, not the game time, so {@link GlyphidBrain#repathDue} staggers records created together. */
    public int tickCount;

    public int task = GlyphidTasks.TASK_FOLLOW;
    public int taskX;
    public int taskY;
    public int taskZ;

    public boolean hasHome;
    public int homeX;
    public int homeY;
    public int homeZ;

    public boolean hasRally;
    public int rallyX;
    public int rallyY;
    public int rallyZ;

    public int squad;

    private final GlyphidMind mind = new GlyphidMind();

    /** Set once the brain gives up walking round something: a request to become a body that can chew. */
    public boolean wantsChew;
    /** Set by anything that hurts this record. A wounded glyphid becomes real; a fight needs a body. */
    public boolean wounded;

    private double pushX;
    private double pushZ;
    /**
     * The last place the brain told this record to walk to. {@code Move.NONE} means "carry on with the walk
     * you are on", which for an entity is its navigator; a record has none, so without this it would take one
     * step per repath interval and stand still in between.
     */
    private boolean hasHop;
    private double hopX;
    private double hopY;
    private double hopZ;
    private boolean hopSampled;
    /** Ground height and the column it was read for, so the heightmap is re-read only when it changes. */
    private int groundColumnX = Integer.MIN_VALUE;
    private int groundColumnZ = Integer.MIN_VALUE;
    private double ground;

    public SimGlyphid(int id, GlyphidCaste caste) {
        this.id = id;
        this.caste = caste;
        this.health = (float) stats().health();
    }

    public GlyphidStats.StatBundle stats() {
        return caste.stats();
    }

    // --- carrier ---

    @Override
    public int carrierId() {
        return id;
    }

    @Override
    public boolean carrierAlive() {
        return health > 0.0F;
    }

    @Override
    public GlyphidMind mind() {
        return mind;
    }

    @Override
    public double carrierX() {
        return x;
    }

    @Override
    public double carrierY() {
        return y;
    }

    @Override
    public double carrierZ() {
        return z;
    }

    @Override
    public double carrierWidth() {
        return caste.type().getDimensions().width();
    }

    @Override
    public boolean carrierPushable() {
        return carrierAlive();
    }

    @Override
    public void carrierPush(double dx, double dz) {
        pushX += dx;
        pushZ += dz;
    }

    /** The carrier form, for callers that do not know which tier they hold. Same code, world read direct. */
    @Override
    public GlyphidSnapshot snapshot(ServerLevel level) {
        return snapshot(new SimWorldLive(level));
    }

    @Override
    public void apply(ServerLevel level, GlyphidPlan plan) {
        apply(new SimWorldLive(level), plan);
    }

    /**
     * What the brain is allowed to see. No target, since a record with something to fight has already been
     * promoted, and no navigator, so the walk is always "done".
     */
    public GlyphidSnapshot snapshot(SimWorld world) {
        boolean stagger = SwarmBench.staggerSearches;
        // Asked once and used twice: height and field come out of one lookup, and a record that is not
        // marching never puts its task position into the prefetch's working set.
        SimWorld.Destination destination = task == GlyphidTasks.TASK_FOLLOW
                ? world.destination(taskX, taskY, taskZ)
                : SimWorld.Destination.NONE;
        if (destination.height() != SimWorld.UNKNOWN
                && GlyphidBrain.repathDue(mind, tickCount, id, true, stagger)) {
            taskY = destination.height();
        }
        return new GlyphidSnapshot(
                id,
                tickCount,
                new Vec3(x, y, z),
                task,
                false,
                0,
                taskX,
                taskY,
                taskZ,
                atDestination(),
                false,
                false,
                true,
                null,
                0.0,
                false,
                false,
                SwarmBench.chargeMelee,
                stagger,
                flowStep(destination));
    }

    public void apply(SimWorld world, GlyphidPlan plan) {
        prevX = x;
        prevY = y;
        prevZ = z;

        switch (plan.move()) {
            case FLOW ->
                // The field hands back the floor of the column, so the step is already a world position.
                    walk(world, plan.hopX() + 0.5, plan.hopY(), plan.hopZ() + 0.5, false);
            case PATH ->
                // No pathfinder. The hop is a waypoint along the bearing, which is all a straight walk needs.
                    aim(world, plan.hopX() + 0.5, plan.hopY(), plan.hopZ() + 0.5, true);
            case CHARGE -> {
                Vec3 destination = plan.destination();
                if (destination != null) {
                    aim(world, destination.x, destination.y, destination.z, true);
                }
            }
            case CHEW -> wantsChew = true;
            case NONE -> {
                if (hasHop) {
                    walk(world, hopX, hopY, hopZ, hopSampled);
                } else {
                    drift(world);
                }
            }
            case STOP -> {
                hasHop = false;
                drift(world);
            }
        }

        if (plan.elapsed() > 0) {
            // A record always "found" its route, never having searched, so the backoff and the stuck timer
            // run entirely off whether it is closing on the destination.
            Vec3 chew = GlyphidBrain.resolve(plan, mind, true);
            if (chew != null) {
                wantsChew = true;
            }
        }
    }

    /**
     * Ask the shared field which way, on the same terms as the entity tier. Reading one is an index and eight
     * comparisons, which is why the worker can do it; it is only written after the pass has joined.
     */
    private @Nullable Vec3 flowStep(SimWorld.Destination destination) {
        GlyphidFlowField field = destination.field();
        if (field == null || mind.chewing) {
            return null;
        }
        double[] step = field.step(x, y, z);
        return step == null ? null : new Vec3(step[0], step[1], step[2]);
    }

    /** Take a new walk and remember it, for the nineteen ticks in twenty the brain leaves it alone. */
    private void aim(SimWorld world, double tx, double ty, double tz, boolean sampleGround) {
        hasHop = true;
        hopX = tx;
        hopY = ty;
        hopZ = tz;
        hopSampled = sampleGround;
        walk(world, tx, ty, tz, sampleGround);
    }

    /**
     * Move one tick toward a point.
     *
     * @param sampleGround true when the height at the far end is a guess and the surface should be consulted.
     *                     False for a flow step, whose height came out of the field and is already the floor
     *                     of a column something can stand in.
     */
    private void walk(SimWorld world, double tx, double ty, double tz, boolean sampleGround) {
        double speed = stats().movementSpeed() * SPEED_PER_ATTRIBUTE;
        double dx = tx - x;
        double dz = tz - z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance > 1.0E-4) {
            double reach = Math.min(distance, speed);
            x += dx / distance * reach;
            z += dz / distance * reach;
            yRot = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        }
        spendPush();
        settleOnto(sampleGround ? groundAt(world, x, z) : ty);
    }

    /** Standing still, but still on the ground and still shoved by the crowd. */
    private void drift(SimWorld world) {
        spendPush();
        settleOnto(groundAt(world, x, z));
    }

    private void spendPush() {
        x += pushX;
        z += pushZ;
        pushX = 0.0;
        pushZ = 0.0;
    }

    /** Approach a new floor rather than snapping to it, so a climb takes as long as one and a drop falls. */
    private void settleOnto(double floor) {
        double delta = floor - y;
        if (Math.abs(delta) <= CLIMB_RATE) {
            y = floor;
        } else {
            y += Math.copySign(delta > 0.0 ? CLIMB_RATE : Math.min(MAX_FALL, -delta), delta);
        }
    }

    /**
     * Surface height in a column, re-read only when the column changes — about one lookup every five ticks,
     * which is this tier's entire cost of knowing about terrain.
     *
     * <p>An unloaded chunk and an unprefetched column want the same answer: keep the height already held and
     * retry next tick, which is why the column cache is not advanced.
     */
    private double groundAt(SimWorld world, double px, double pz) {
        int columnX = Mth.floor(px);
        int columnZ = Mth.floor(pz);
        if (columnX == groundColumnX && columnZ == groundColumnZ) {
            return ground;
        }
        int height = world.height(columnX, columnZ);
        if (height == SimWorld.UNKNOWN) {
            // Keeping the last height is what a warband record does for a whole crossing.
            return ground == 0.0 ? y : ground;
        }
        groundColumnX = columnX;
        groundColumnZ = columnZ;
        ground = height;
        return ground;
    }

    public boolean atDestination() {
        double dx = taskX - x;
        double dy = taskY - y;
        double dz = taskZ - z;
        return dx * dx + dy * dy + dz * dz <= GlyphidTasks.DEFAULT_DESTINATION_RADIUS_SQ;
    }

    /**
     * Take a hit. No damage source and no armour model: nothing but area damage can reach a record.
     *
     * @return true if this killed it
     */
    public boolean hurt(float amount) {
        health -= amount;
        wounded = true;
        return health <= 0.0F;
    }

    /** Take everything off a live body that has to survive becoming a record and back again. */
    public static SimGlyphid fromEntity(int id, EntityGlyphid glyphid) {
        SimGlyphid sim = new SimGlyphid(id, GlyphidCaste.byType(glyphid.getType()));
        sim.x = sim.prevX = glyphid.getX();
        sim.y = sim.prevY = glyphid.getY();
        sim.z = sim.prevZ = glyphid.getZ();
        sim.yRot = glyphid.getYRot();
        sim.health = glyphid.getHealth();
        sim.armor = glyphid.armor();
        sim.subtype = glyphid.subtype();
        sim.persistent = glyphid.isPersistenceRequired();
        sim.garrison = glyphid.garrison;
        sim.tickCount = glyphid.tickCount;
        sim.task = glyphid.getCurrentTask();
        sim.taskX = glyphid.taskX;
        sim.taskY = glyphid.taskY;
        sim.taskZ = glyphid.taskZ;
        sim.hasHome = glyphid.hasHome;
        sim.homeX = glyphid.homeX;
        sim.homeY = glyphid.homeY;
        sim.homeZ = glyphid.homeZ;
        sim.hasRally = glyphid.hasRally;
        sim.rallyX = glyphid.rallyX;
        sim.rallyY = glyphid.rallyY;
        sim.rallyZ = glyphid.rallyZ;
        sim.squad = glyphid.squad;
        sim.copyMindFrom(glyphid.mind());
        return sim;
    }

    /**
     * Build the body back. A round trip is invisible apart from the entity id, which is not preserved —
     * there is no per-glyphid identity worth paying for (§16).
     */
    public @Nullable EntityGlyphid toEntity(ServerLevel level) {
        EntityType<? extends EntityGlyphid> type = caste.type();
        EntityGlyphid glyphid = type.create(level);
        if (glyphid == null) {
            return null;
        }
        glyphid.moveTo(x, y, z, yRot, 0.0F);
        glyphid.setHealth(Math.max(1.0F, health));
        glyphid.setArmor(armor);
        glyphid.setSubtype(subtype);
        if (persistent) {
            glyphid.setPersistenceRequired();
        }
        glyphid.garrison = garrison;
        glyphid.hasHome = hasHome;
        glyphid.homeX = homeX;
        glyphid.homeY = homeY;
        glyphid.homeZ = homeZ;
        glyphid.hasRally = hasRally;
        glyphid.rallyX = rallyX;
        glyphid.rallyY = rallyY;
        glyphid.rallyZ = rallyZ;
        glyphid.squad = squad;
        glyphid.taskX = taskX;
        glyphid.taskY = taskY;
        glyphid.taskZ = taskZ;
        glyphid.setCurrentTask(task, null);
        // The memory goes across too, or the stuck timer that was about to turn this into a digger restarts
        // from zero on every crossing.
        glyphid.mind().copyFrom(mind);
        return glyphid;
    }

    private void copyMindFrom(GlyphidMind source) {
        mind.copyFrom(source);
    }

    // --- persistence ---

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("caste", caste.name());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yRot", yRot);
        tag.putFloat("health", health);
        tag.putByte("armor", armor);
        tag.putByte("subtype", subtype);
        tag.putBoolean("persistent", persistent);
        tag.putBoolean("garrison", garrison);
        tag.putInt("age", tickCount);
        tag.putInt("task", task);
        tag.putInt("taskX", taskX);
        tag.putInt("taskY", taskY);
        tag.putInt("taskZ", taskZ);
        tag.putBoolean("hasHome", hasHome);
        tag.putInt("homeX", homeX);
        tag.putInt("homeY", homeY);
        tag.putInt("homeZ", homeZ);
        tag.putBoolean("hasRally", hasRally);
        tag.putInt("rallyX", rallyX);
        tag.putInt("rallyY", rallyY);
        tag.putInt("rallyZ", rallyZ);
        tag.putInt("squad", squad);
        return tag;
    }

    public static SimGlyphid load(CompoundTag tag) {
        GlyphidCaste caste = GlyphidCaste.byName(tag.getString("caste"));
        SimGlyphid sim = new SimGlyphid(tag.getInt("id"), caste == null ? GlyphidCaste.GRUNT : caste);
        sim.x = sim.prevX = tag.getDouble("x");
        sim.y = sim.prevY = tag.getDouble("y");
        sim.z = sim.prevZ = tag.getDouble("z");
        sim.yRot = tag.getFloat("yRot");
        sim.health = tag.getFloat("health");
        sim.armor = tag.getByte("armor");
        sim.subtype = tag.getByte("subtype");
        sim.persistent = tag.getBoolean("persistent");
        sim.garrison = tag.getBoolean("garrison");
        sim.tickCount = tag.getInt("age");
        sim.task = tag.getInt("task");
        sim.taskX = tag.getInt("taskX");
        sim.taskY = tag.getInt("taskY");
        sim.taskZ = tag.getInt("taskZ");
        sim.hasHome = tag.getBoolean("hasHome");
        sim.homeX = tag.getInt("homeX");
        sim.homeY = tag.getInt("homeY");
        sim.homeZ = tag.getInt("homeZ");
        sim.hasRally = tag.getBoolean("hasRally");
        sim.rallyX = tag.getInt("rallyX");
        sim.rallyY = tag.getInt("rallyY");
        sim.rallyZ = tag.getInt("rallyZ");
        sim.squad = tag.getInt("squad");
        sim.mind.reset(sim.id, sim.x, sim.z);
        return sim;
    }
}
