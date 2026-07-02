package com.wf.wfballistics.sim;

import net.minecraft.world.phys.Vec3;


public final class CollisionPredictor {
    private CollisionPredictor() {
    }

    /**
     * @return the predicted collision area if the two tracks close to within
     * {@link MissileSimConfig#INTERCEPT_DISTANCE} inside the horizon, else null.
     */
    public static Result predict(SimMissile interceptor, SimMissile target) {
        Vec3 iPos = interceptor.pos;
        Vec3 tPos = target.pos;
        double iSpeed = interceptor.speed;
        double tSpeed = target.speed;
        double tY = target.simY;
        Vec3 groundTarget = target.target;

        for (int t = 1; t <= MissileSimConfig.PREDICT_HORIZON_TICKS; t++) {
            double dx = groundTarget.x - tPos.x;
            double dz = groundTarget.z - tPos.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz > 1.0E-6) {
                double step = Math.min(tSpeed, horiz);
                tPos = new Vec3(tPos.x + dx / horiz * step, tY, tPos.z + dz / horiz * step);
            }
            Vec3 to = tPos.subtract(iPos);
            double dist = to.length();
            if (dist > 1.0E-6) {
                double step = Math.min(iSpeed, dist);
                iPos = iPos.add(to.scale(step / dist));
            }

            double sep = iPos.distanceTo(tPos);
            if (sep <= MissileSimConfig.INTERCEPT_DISTANCE) {
                return new Result(iPos.add(tPos).scale(0.5), t, sep);
            }
        }
        return null;
    }

    public record Result(Vec3 point, int ticks, double minDist) {
    }
}
