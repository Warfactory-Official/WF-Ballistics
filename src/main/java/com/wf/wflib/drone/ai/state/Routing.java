package com.wf.wflib.drone.ai.state;

import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.nav.DronePath;
import net.minecraft.world.phys.Vec3;

/** Turns a drone's planned route into the point its handler should be flying at. */
public final class Routing {

    private Routing() {
    }

    /**
     * @param fallback where the drone is ultimately going
     * @return the point to steer at, with the height to be at when it gets there. Comes from the route when
     *      one is available and still describes this journey; otherwise it is the destination at cruise height,
     *      which is what the drone did before it could plan at all.
     */
    public static Vec3 aim(DroneSnapshot self, Vec3 fallback) {
        DronePath path = self.nav().path();
        if (path == null || path.isEmpty() || path.stale(self.pos(), fallback, self.gameTime())) {
            return new Vec3(fallback.x, self.cruiseY(), fallback.z);
        }
        Vec3 heading = path.carrot(self.pos());
        double altitude = path.carrot(self.pos(), DronePath.ALTITUDE_LEAD).y;
        return new Vec3(heading.x, altitude, heading.z);
    }
}
