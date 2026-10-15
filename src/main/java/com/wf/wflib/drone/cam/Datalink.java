package com.wf.wflib.drone.cam;

import com.wf.wflib.api.DetachedBodyHost;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.env.Interference;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Operator <-> remote body link quality, 0..1: feeds, remote pilots, TV rounds. Range x RF jamming
 * ({@link Interference} RADAR areas at either end); EMP'd body ({@link #jam}) = 0.
 */
public final class Datalink {

    private static final Map<UUID, Long> JAMMED = new HashMap<>();

    private Datalink() {
    }

    /** @param operator where the operator really is ({@link #operatorAt}, monitor, eye) */
    public static float quality(ServerLevel level, CameraSpec spec, Vec3 operator, Vec3 body) {
        return spec.linkAt(operator.distanceTo(body)) * Interference.factorAt(level, Band.RADAR, operator)
                * Interference.factorAt(level, Band.RADAR, body);
    }

    public static float quality(ServerLevel level, CameraSpec spec, Vec3 operator, Entity body, Vec3 eye) {
        return jammed(level, body) ? 0.0f : quality(level, spec, operator, eye);
    }

    /** EMP: {@code body}'s link dead for {@code ticks}. */
    public static void jam(Entity body, int ticks) {
        JAMMED.merge(body.getUUID(), body.level().getGameTime() + ticks, Math::max);
    }

    public static boolean jammed(ServerLevel level, Entity body) {
        Long until = JAMMED.get(body.getUUID());
        if (until == null) {
            return false;
        }
        if (until <= level.getGameTime()) {
            JAMMED.remove(body.getUUID());
            return false;
        }
        return true;
    }

    /** Riding a remote body => the parked body's anchor. */
    public static Vec3 operatorAt(Entity operator) {
        Vec3 anchor = operator.getVehicle() instanceof DetachedBodyHost host
                ? host.getDetachedBodyAnchor(operator) : null;
        return anchor != null ? anchor : operator.position();
    }

    public static boolean usable(float quality) {
        return quality > CameraFeed.USABLE_LINK;
    }

    public static void shutdown() {
        JAMMED.clear();
    }
}
