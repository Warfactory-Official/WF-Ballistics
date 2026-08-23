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
 * One glyphid, without an entity: §2's T1 tier.
 *
 * <p>A glyphid nobody is standing near does not need a hitbox, a collision sweep, a navigator, an attribute
 * map, a data-watcher table or a slot in the entity tick list — and measured, those are where its cost is.
 * Movement and the base tick were 53% of what a marching swarm spent, and no amount of making them cheaper
 * gets to zero. Having fewer of them does.
 *
 * <p>What is left is a record that walks. It runs the same {@link GlyphidBrain} against the same
 * {@link GlyphidMind} as a live body does — that boundary is exactly what phase 2 was for — and carries the
 * decision out by moving a double, so a tick of it is arithmetic and, at most, one heightmap read.
 *
 * <p><b>Two navigation models, and the honest version of each.</b> Inside a {@link GlyphidFlowField} it walks
 * the field, which only ever crosses columns a glyphid could stand in, so it does not pass through walls and
 * does not need collision to avoid them. Outside one it walks straight at its destination over the surface
 * height, which is the same trade {@code SimDrone} makes by holding altitude and flying straight, and the
 * same one {@code Warband} makes crossing the map. It is a lie about terrain that is only told where nobody
 * can see it, and the moment a glyphid stops making progress it becomes a real body that can chew.
 *
 * <p><b>What it deliberately cannot do:</b> fight, swim, fly, dig, or found a nest. Each of those is a world
 * write or a physics problem, and each promotes the record back to an entity rather than being reimplemented
 * here — see {@link SimGlyphidManager}. The tier is the approach march, not a second game.
 */
public final class SimGlyphid implements GlyphidCarrier {

    /**
     * Blocks per tick per point of the {@code MOVEMENT_SPEED} attribute.
     *
     * <p>Calibrated against the entity tier rather than derived, and the difference between the two is the
     * whole reason it is a measured constant. Vanilla ground movement accelerates toward a target velocity
     * against block friction, whose terminal speed for a grunt is about 0.375 blocks a tick — but a walking
     * glyphid never reaches it, because path following slows at every node and every corner. Measured over a
     * 160-block march it covers 0.130. A record walks in a straight line at whatever this says, so setting
     * it from the physics would have made the sim tier <em>56% faster than the entity tier</em>: a swarm
     * arriving in two waves, which reads as a design decision rather than as a bug.
     *
     * <p>Checked, not asserted: {@code swarmbench tiers} reports blocks per tick for each tier off the same
     * run, which is what this number was set from and what would catch it drifting.
     */
    private static final double SPEED_PER_ATTRIBUTE = 0.55;
    /**
     * Blocks of height gained or lost per tick. A glyphid climbs, so the field connects columns up to eight
     * blocks apart vertically; snapping straight to the new floor would teleport it up the side of a wall.
     */
    private static final double CLIMB_RATE = 0.4;
    /**
     * How far the ground may be re-read from. Beyond this the record keeps the height it had, which is what
     * happens where the chunk is not loaded.
     */
    private static final double MAX_FALL = 4.0;

    /**
     * Negative, and unique per level. Entity ids are positive, so a client can key one store by id and tell
     * the tiers apart without a flag, and {@code GlyphidMind}'s stagger arithmetic is a {@code floorMod}
     * that does not care about the sign.
     */
    public final int id;
    public final GlyphidCaste caste;

    public double x;
    public double y;
    public double z;
    /**
     * Where it was last tick, so the client can interpolate and the walk cycle has something to run off.
     */
    public double prevX;
    public double prevY;
    public double prevZ;
    public float yRot;

    public float health;
    public byte armor = EntityGlyphid.FULL_ARMOR;
    public byte subtype = EntityGlyphid.TYPE_NORMAL;
    public boolean persistent;

    /**
     * Its own age, not the game time: {@link GlyphidBrain#repathDue} staggers off it so bodies that were
     * created together still spread their decisions out.
     */
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

    /**
     * Set once the brain gives up on walking round something. A record cannot chew — that is a world write —
     * so this is a request to become a body that can.
     */
    public boolean wantsChew;
    /**
     * Set by anything that hurts this record. A wounded glyphid becomes real: it is in a fight, and a fight
     * is the thing this tier is not for.
     */
    public boolean wounded;

    private double pushX;
    private double pushZ;
    /**
     * The last place the brain told this record to walk to, and whether there is one.
     *
     * <p>Load-bearing, and the one thing a record needs that a body gets for free. {@code Move.NONE} means
     * "carry on with the walk you are already on", which for an entity is a navigator following a path it
     * was given twenty ticks ago. A record has no navigator, so without somewhere to remember the hop it
     * would take one step every repath interval and stand still in between — a swarm that appears to be
     * walking at a twentieth of its speed, which reads as a movement-speed bug rather than a missing field.
     */
    private boolean hasHop;
    private double hopX;
    private double hopY;
    private double hopZ;
    private boolean hopSampled;
    /**
     * Ground height under the column it is standing in, and which column that was, so the heightmap is only
     * re-read when it changes rather than every tick.
     */
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

    /**
     * The carrier form, for anything that drives a record through {@link GlyphidCarrier} without knowing
     * which tier it has. Routes to the same code as the pass, with the world read straight through.
     */
    @Override
    public GlyphidSnapshot snapshot(ServerLevel level) {
        return snapshot(new SimWorldLive(level));
    }

    @Override
    public void apply(ServerLevel level, GlyphidPlan plan) {
        apply(new SimWorldLive(level), plan);
    }

    /**
     * What the brain is allowed to see. Shorter than the entity's by everything a record cannot have: there
     * is no target, because a record that has something to fight has already been promoted, and no navigator,
     * so the walk is always "done" and the brain is never waiting on one to finish.
     */
    public GlyphidSnapshot snapshot(SimWorld world) {
        boolean stagger = SwarmBench.staggerSearches;
        // Asked once and used twice. Both the destination's height and the field that leads to it are only
        // wanted while marching, and both come out of the same lookup, so a record that is not marching does
        // not put its task position into the prefetch's working set at all.
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
                // The field hands back the floor of the column it is pointing at, so there is nothing left
                // to look up: the step is already a position in the world. Re-asked every tick, so there is
                // nothing to remember either.
                    walk(world, plan.hopX() + 0.5, plan.hopY(), plan.hopZ() + 0.5, false);
            case PATH ->
                // No pathfinder to search with. The hop is still the right place to head for -- it is a
                // waypoint along the bearing, which is all a straight walk needs -- and the height comes off
                // the surface, which is the whole of this tier's terrain model.
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
            // A record always "found" its route, because it never searched for one. The backoff and the
            // stuck timer therefore run entirely off whether it is closing on the destination, which is the
            // signal that works for both tiers and the only one this one has.
            Vec3 chew = GlyphidBrain.resolve(plan, mind, true);
            if (chew != null) {
                wantsChew = true;
            }
        }
    }

    /**
     * Ask the shared field which way, on the same terms the entity tier asks: marching only, and null where
     * there is no field or it has not flooded this far.
     *
     * <p>The field itself is a pair of dense arrays and reading one is eight comparisons and an index, which
     * is why the pass can do it from a worker at all. It is only ever written by
     * {@code GlyphidFlowFields.tick}, which runs on the world thread after the pass has been joined.
     */
    private @Nullable Vec3 flowStep(SimWorld.Destination destination) {
        GlyphidFlowField field = destination.field();
        if (field == null || mind.chewing) {
            return null;
        }
        double[] step = field.step(x, y, z);
        return step == null ? null : new Vec3(step[0], step[1], step[2]);
    }

    /**
     * Take a new walk, and remember it. The record keeps walking to this point on every tick the brain
     * leaves it alone, which is nineteen ticks in twenty.
     */
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

    /**
     * Standing still, but still on the ground and still shoved by the crowd. Without this a stopped glyphid
     * inside a pile is the one thing nothing can move.
     */
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

    /**
     * Approach a new floor height rather than snapping to it, so climbing a wall takes about as long as an
     * entity takes to climb it and a drop is a fall rather than a teleport.
     */
    private void settleOnto(double floor) {
        double delta = floor - y;
        if (Math.abs(delta) <= CLIMB_RATE) {
            y = floor;
        } else {
            y += Math.copySign(delta > 0.0 ? CLIMB_RATE : Math.min(MAX_FALL, -delta), delta);
        }
    }

    /**
     * Surface height in a column, re-read only when the column changes. At a fifth of a block a tick that is
     * one lookup every five ticks or so, which is this tier's entire cost of knowing about terrain.
     *
     * <p>The unknown case covers two things that want the same answer: a chunk that is not loaded, and a
     * column the prefetch has not been given yet. Both keep the height already held and ask again next tick —
     * the column cache is deliberately not advanced, so the retry happens. A record already approaches a new
     * floor rather than snapping to it, so a tick of lag on the answer is below what the movement resolves.
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
     * Take a hit. No damage source and no armour model: a record has no plates to knock off and nothing to
     * shoot at it but area damage, which is the one thing that does not care where it lands.
     *
     * @return true if this killed it
     */
    public boolean hurt(float amount) {
        health -= amount;
        wounded = true;
        return health <= 0.0F;
    }

    /**
     * Take everything off a live body that has to survive becoming a record and back again.
     */
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
     * Build the body back. Everything {@link #fromEntity} took is put back, so a round trip is invisible
     * apart from the entity id — which is deliberately not preserved, because §16's carrier note says there
     * is no per-glyphid identity here worth paying for.
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
        // The memory goes across too. Without it a glyphid that spent forty ticks failing to get anywhere
        // arrives in the world with a fresh sixty ticks of patience, and the stuck timer that was about to
        // turn it into a digger starts again from zero every time it crosses the boundary.
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
