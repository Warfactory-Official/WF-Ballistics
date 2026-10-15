package com.wf.wflib.drone;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** One-step mission for a body already in the world: no pad, no spawn. */
public final class DroneTasking {

    private DroneTasking() {
    }

    /**
     * Grounded => home = where it stands. Airborne => replaces the program, flies there via TRANSIT.
     *
     * @return null on success, else why not
     */
    @Nullable
    public static String assign(ControllableDrone body, @Nullable UUID operator, DroneTask.Kind kind, Vec3 at) {
        if (!body.supports(kind)) {
            return body.callsign() + " cannot fly " + kind.id();
        }
        if (body.orders().isClassified()) {
            return "no link";
        }
        DroneState state = body.flight().getDroneState();
        if (state != DroneState.IDLE && !state.powered()) {
            return body.callsign() + " is " + state.name().toLowerCase(java.util.Locale.ROOT);
        }
        body.setOperatorId(operator);
        if (state == DroneState.IDLE) {
            body.route().setExfil(body.asEntity().position());
        }
        body.orders().setProgram(DroneProgram.of(List.of(kind.at(at))));
        if (state != DroneState.IDLE) {
            body.flight().setState(DroneState.TRANSIT);
        }
        return null;
    }
}
