package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * How a glyphid walks somewhere, for every goal that needs it.
 *
 * <p>Two rules, both of which cost real time to learn:
 *
 * <p><b>Path in hops, with the range named.</b> Vanilla's {@code createPath(pos, accuracy)} takes its range
 * from the mob's follow range — 16 for a monster — and the pathfinder will not expand past it, so a distant
 * destination comes back as a stub that walks two blocks and stops. A waypoint {@link #HOP} blocks along the
 * bearing, asked for with an explicit range, always yields a usable route.
 *
 * <p><b>Back off on not moving, not on not pathing.</b> The pathfinder almost never returns nothing: when the
 * destination is unreachable it hands back a partial route to the best node it found, so the expensive case is
 * also the one that reads as success. Displacement is the only honest signal, and a glyphid that has not moved
 * in a second will not move in the next one either — it should stop paying for a full A* to find that out.
 *
 * <p>When the way is blocked, the glyphid bites through it. That is the whole reason a swarm is frightening:
 * terrain slows it rather than stopping it.
 */
public abstract class GlyphidPathingGoal extends Goal {

    /**
     * How far ahead the next pathing waypoint is placed.
     */
    protected static final int HOP = 16;
    /**
     * Path range asked for, which has to clear {@link #HOP} with room to spare.
     */
    protected static final int PATH_RANGE = HOP + 8;
    /**
     * Never repath more often than this. One A* per glyphid per second is already the dominant cost in a
     * swarm; the point of hopping is to amortise it over the walk, not to pay it every tick.
     */
    protected static final int REPATH_MIN = 20;
    /**
     * Repath at least this often even while a path is still being walked, so the bearing stays fresh.
     */
    protected static final int REPATH_MAX = 100;
    /**
     * How long a glyphid must fail to make progress before it stops trying to walk and starts chewing.
     */
    protected static final int STUCK_TICKS = 60;
    /**
     * Progress, in blocks, that counts as not being stuck.
     */
    protected static final double PROGRESS = 1.0;
    /**
     * How far ahead to look for the thing in the way.
     */
    protected static final double CHEW_REACH = 3.0;

    protected final EntityGlyphid glyphid;
    private final double speed;

    private int sinceRepath;
    private int retryAfter = REPATH_MIN;
    private int stuckFor;
    private double lastX;
    private double lastZ;

    protected GlyphidPathingGoal(EntityGlyphid glyphid, double speed) {
        this.glyphid = glyphid;
        this.speed = speed;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * Where this goal wants the glyphid to be, resolved fresh on each repath rather than each tick — a
     * heightmap read per glyphid per tick is not free at swarm scale.
     *
     * @return null if the destination has gone away, which stops the walk
     */
    protected abstract @Nullable Vec3 destination();

    @Override
    public void start() {
        sinceRepath = REPATH_MIN;
        retryAfter = REPATH_MIN;
        stuckFor = 0;
        lastX = glyphid.getX();
        lastZ = glyphid.getZ();
    }

    @Override
    public void stop() {
        glyphid.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Repathing is driven by the walk finishing rather than by a fixed interval. Repathing on a timer throws
     * away a live path the glyphid is a fraction of the way through and pays for a fresh A* to replace it,
     * which at swarm scale is the whole cost of the swarm.
     */
    protected void advance() {
        sinceRepath++;
        if (sinceRepath < retryAfter) {
            return;
        }
        if (!glyphid.getNavigation().isDone() && sinceRepath < REPATH_MAX) {
            return;
        }
        int elapsed = sinceRepath;
        sinceRepath = 0;

        double moved = Math.abs(glyphid.getX() - lastX) + Math.abs(glyphid.getZ() - lastZ);
        lastX = glyphid.getX();
        lastZ = glyphid.getZ();

        Vec3 target = destination();
        if (target == null) {
            return;
        }
        boolean progressing = pathToward(target) && moved > PROGRESS;
        retryAfter = progressing ? REPATH_MIN : Math.min(retryAfter * 2, REPATH_MAX);

        if (progressing) {
            stuckFor = 0;
            return;
        }
        stuckFor += elapsed;
        if (stuckFor >= STUCK_TICKS) {
            stuckFor = 0;
            chewToward(target);
        }
    }

    /**
     * Force the next tick to repath. For a goal whose destination moves out from under it.
     */
    protected void repathNow() {
        sinceRepath = retryAfter;
    }

    /**
     * @return true if a path was found and accepted
     */
    private boolean pathToward(Vec3 target) {
        Vec3 pos = glyphid.position();
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) {
            return true;
        }

        double reach = Math.min(distance, HOP);
        int hopX = Mth.floor(pos.x + dx / distance * reach);
        int hopZ = Mth.floor(pos.z + dz / distance * reach);
        int hopY;
        if (distance <= HOP) {
            // Close enough to aim at the thing itself; a heightmap sample here would put the waypoint on the
            // roof of whatever the target is standing under.
            hopY = Mth.floor(target.y);
        } else if (glyphid.level().hasChunk(hopX >> 4, hopZ >> 4)) {
            hopY = glyphid.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hopX, hopZ);
        } else {
            hopY = Mth.floor(pos.y);
        }

        // Accuracy 1, range named explicitly: see PATH_RANGE.
        Path path = glyphid.getNavigation().createPath(new BlockPos(hopX, hopY, hopZ), 1, PATH_RANGE);
        return path != null && glyphid.getNavigation().moveTo(path, speed);
    }

    /**
     * Bite through whatever is standing between this glyphid and where it is going.
     */
    protected void chewToward(Vec3 target) {
        if (!glyphid.canDig()) {
            return;
        }
        Vec3 eye = glyphid.getEyePosition();
        double dx = target.x - glyphid.getX();
        double dz = target.z - glyphid.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4) {
            return;
        }
        Vec3 ahead = eye.add(dx / distance * CHEW_REACH, 0, dz / distance * CHEW_REACH);

        BlockHitResult hit = glyphid.level().clip(new ClipContext(eye, ahead, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, glyphid));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos obstruction = hit.getBlockPos();
        glyphid.digAt(obstruction.getX(), obstruction.getY(), obstruction.getZ());
    }
}
