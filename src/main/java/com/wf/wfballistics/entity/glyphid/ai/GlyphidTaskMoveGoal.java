package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Walks a glyphid to the destination its orders name.
 *
 * <p>Upstream has no goal for this: movement under orders is issued by its async decision layer, which this
 * port replaced. Without something here a glyphid handed a destination simply stands in it, so a materialised
 * warband would arrive and never attack.
 *
 * <p>Paths in hops rather than straight at the destination. Vanilla pathfinding is bounded by follow range, so
 * a target two thousand blocks away yields no path at all and the swarm never sets off; a waypoint {@link #HOP}
 * blocks along the bearing always yields one, and re-aiming it on every repath is what keeps the march on its
 * line.
 *
 * <p>When the hop cannot be pathed either — a wall, a cliff, a sealed base — the glyphid bites through it. That
 * is the whole reason a swarm is frightening: terrain slows it rather than stopping it.
 */
public class GlyphidTaskMoveGoal extends Goal {

    /**
     * How far ahead the next pathing waypoint is placed.
     */
    private static final int HOP = 16;
    /**
     * Path range asked for, which has to clear {@link #HOP} with room to spare.
     *
     * <p>The default is the mob's follow range — 16 for a monster — and the pathfinder refuses to expand
     * beyond it, so a hop at the limit comes back as a stub that walks a couple of blocks and stops. Upstream
     * hits the same wall and answers it the same way, by naming the range explicitly.
     */
    private static final int PATH_RANGE = HOP + 8;
    /**
     * Never repath more often than this. One A* per glyphid per second is already the dominant cost in a
     * swarm; the point of hopping is to amortise it over the walk, not to pay it every tick.
     */
    private static final int REPATH_MIN = 20;
    /**
     * Repath at least this often even while a path is still being walked, so the bearing stays fresh.
     */
    private static final int REPATH_MAX = 100;
    /**
     * How long a glyphid must fail to make progress before it stops trying to walk and starts chewing.
     */
    private static final int STUCK_TICKS = 60;
    /**
     * Progress, in blocks, that counts as not being stuck.
     */
    private static final double PROGRESS = 1.0;
    /**
     * How far ahead to look for the thing in the way.
     */
    private static final double CHEW_REACH = 3.0;

    private final EntityGlyphid glyphid;
    private final double speed;

    private int sinceRepath;
    private int retryAfter = REPATH_MIN;
    private int stuckFor;
    private double lastX;
    private double lastZ;

    public GlyphidTaskMoveGoal(EntityGlyphid glyphid, double speed) {
        this.glyphid = glyphid;
        this.speed = speed;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return glyphid.getCurrentTask() == GlyphidTasks.TASK_FOLLOW && !glyphid.isAtDestination();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

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
    @Override
    public void tick() {
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

        resolveDestinationY();
        boolean progressing = pathToward() && moved > PROGRESS;

        // Backing off is keyed on getting somewhere, not on whether a path came back. The pathfinder almost
        // never returns nothing: when the target is out of reach it hands back a partial route to the best
        // node it found, so the expensive case is also the one that reads as success. A glyphid that has not
        // moved in a second will not move in the next one either, and it should stop paying for a full search
        // every second to find that out.
        retryAfter = progressing ? REPATH_MIN : Math.min(retryAfter * 2, REPATH_MAX);

        if (progressing) {
            stuckFor = 0;
            return;
        }
        stuckFor += elapsed;
        if (stuckFor >= STUCK_TICKS) {
            stuckFor = 0;
            chew();
        }
    }

    /**
     * Pull the destination height onto real terrain once its column is loaded.
     *
     * <p>A warband is given a placeholder height when it materialises, because the base it is marching on is
     * usually still unloaded from where it lands. Left stale, the arrival test never passes and the swarm
     * mills around on top of its own target.
     */
    private void resolveDestinationY() {
        int x = glyphid.taskX;
        int z = glyphid.taskZ;
        if (!glyphid.level().hasChunk(x >> 4, z >> 4)) {
            return;
        }
        glyphid.taskY = glyphid.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /**
     * @return true if a path was found and accepted
     */
    private boolean pathToward() {
        Vec3 pos = glyphid.position();
        double dx = glyphid.taskX + 0.5 - pos.x;
        double dz = glyphid.taskZ + 0.5 - pos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) {
            return true;
        }

        double reach = Math.min(distance, HOP);
        int hopX = Mth.floor(pos.x + dx / distance * reach);
        int hopZ = Mth.floor(pos.z + dz / distance * reach);
        int hopY = glyphid.level().hasChunk(hopX >> 4, hopZ >> 4)
                ? glyphid.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hopX, hopZ)
                : Mth.floor(pos.y);

        // Accuracy 1, range named explicitly: see PATH_RANGE.
        Path path = glyphid.getNavigation().createPath(new BlockPos(hopX, hopY, hopZ), 1, PATH_RANGE);
        return path != null && glyphid.getNavigation().moveTo(path, speed);
    }

    /**
     * Bite through whatever is standing between this glyphid and where it is going.
     */
    private void chew() {
        if (!glyphid.canDig()) {
            return;
        }
        Vec3 eye = glyphid.getEyePosition();
        double dx = glyphid.taskX + 0.5 - glyphid.getX();
        double dz = glyphid.taskZ + 0.5 - glyphid.getZ();
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
