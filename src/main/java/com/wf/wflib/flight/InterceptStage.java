package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Interceptor homing: fly full-3D straight at the current aim point ({@link FlightContext#target()}, which for an
 * interceptor is the per-tick lead point computed against a moving target) at the missile's cruise speed.
 */
public final class InterceptStage implements FlightStage {

    public static final InterceptStage INSTANCE = new InterceptStage();

    private static final double CLEAR_MARGIN = 3.0;
    private static final int CLEAR_SCAN_RADIUS = 3;
    private static final int MAX_CLEAR_TICKS = 40;

    private InterceptStage() {
    }

    /**
     * @return true once the missile has climbed {@link #CLEAR_MARGIN} above the tallest motion-blocking column
     *      within {@link #CLEAR_SCAN_RADIUS}: i.e. it has topped the silo shaft / depression walls around it.
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
        if (missile.flight().getPhase() == MissileEntity.Phase.ASCEND) {
            return new Vec3(0.0, missile.flight().getCruiseSpeed(), 0.0);
        }
        Vec3 to = ctx.target().subtract(ctx.position());
        double len = to.length();
        if (len < 1.0E-6) {
            // On top of the aim point: keep the current heading rather than producing a zero/NaN direction.
            return missile.getDeltaMovement();
        }
        return to.scale(missile.flight().getCruiseSpeed() / len);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        if (missile.flight().getPhase() == MissileEntity.Phase.ASCEND
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
