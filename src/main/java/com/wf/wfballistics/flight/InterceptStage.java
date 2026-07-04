package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Interceptor homing: fly full-3D straight at the current aim point ({@link FlightContext#target()}, which for
 * an interceptor is the per-tick lead point computed against a moving target) at the missile's cruise speed.
 * Unlike the ground-attack stages this ignores terrain and altitude entirely and climbs/dives freely; the
 * caller still turn-rate-limits the result, so the interceptor arcs onto its lead course instead of snapping.
 *
 * <p>Launch clear: an interceptor launches already armed and starts in {@link MissileEntity.Phase#ASCEND}. While
 * in that phase this stage boosts straight up until the missile has topped the walls immediately around it, then
 * hands off to homing ({@link #next} advances to {@link MissileEntity.Phase#CRUISE}). Without this a missile
 * launched from a silo shaft or a narrow depression would steer horizontally at its target on the very first
 * tick and fly into the enclosing wall. In the open the clear is satisfied within a block or two (and a missile
 * that materialises high in the air clears immediately), so only a boxed-in launch actually climbs.
 *
 * <p>Registered for <em>every</em> phase (ASCEND/CRUISE/ATTACK) so an all-{@code "intercept"} interceptor flies
 * this stage in any phase and survives the id-based {@link FlightProfile} rebuild on reload.
 */
public final class InterceptStage implements FlightStage {

    public static final InterceptStage INSTANCE = new InterceptStage();

    // Blocks the missile must top the surrounding lip by before it is considered clear to home, and the radius
    // (columns) around it scanned for enclosing walls. A small radius keeps an open launch from waiting on
    // distant terrain while still catching the close walls of a silo or narrow depression.
    private static final double CLEAR_MARGIN = 3.0;
    private static final int CLEAR_SCAN_RADIUS = 3;
    private static final int MAX_CLEAR_TICKS = 40;

    private InterceptStage() {
    }

    /**
     * @return true once the missile has climbed {@link #CLEAR_MARGIN} above the tallest motion-blocking column
     * within {@link #CLEAR_SCAN_RADIUS} — i.e. it has topped the silo shaft / depression walls around it.
     */
    private static boolean clearedLaunchWalls(MissileEntity missile) {
        Level level = missile.level();
        int cx = Mth.floor(missile.getX());
        int cz = Mth.floor(missile.getZ());
        int top = level.getMinBuildHeight();
        for (int ox = -CLEAR_SCAN_RADIUS; ox <= CLEAR_SCAN_RADIUS; ox++) {
            for (int oz = -CLEAR_SCAN_RADIUS; oz <= CLEAR_SCAN_RADIUS; oz++) {
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + ox, cz + oz);
                if (h > top) {
                    top = h;
                }
            }
        }
        return missile.getY() >= top + CLEAR_MARGIN;
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        // Launch clear: while still boxed in, boost straight up so the first ticks don't steer into the wall we
        // are climbing out of. Homing resumes the instant next() clears us; the caller's turn-rate limit then
        // arcs the vertical run onto the intercept course.
        if (missile.getPhase() == MissileEntity.Phase.ASCEND) {
            return new Vec3(0.0, missile.getCruiseSpeed(), 0.0);
        }
        Vec3 to = ctx.target().subtract(ctx.position());
        double len = to.length();
        if (len < 1.0E-6) {
            // On top of the aim point: keep the current heading rather than producing a zero/NaN direction.
            return missile.getDeltaMovement();
        }
        return to.scale(missile.getCruiseSpeed() / len);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        // Hand off from the vertical launch-clear to homing once above the enclosing walls; otherwise the
        // interceptor never changes phase (it ends on a kill, a lost target, or lifetime).
        if (missile.getPhase() == MissileEntity.Phase.ASCEND
                && (clearedLaunchWalls(missile) || missile.tickCount >= MAX_CLEAR_TICKS)) {
            return MissileEntity.Phase.CRUISE;
        }
        return null;
    }

    @Override
    public String id() {
        return "intercept";
    }
}
