package com.wf.wflib.drone.dispatch;

import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.drone.DroneTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Remote mission source: PDA dispatch -> nearest permitted dispatcher ({@link DroneDispatchers}). Server side;
 * {@link DroneDispatchers#add} while loaded.
 */
public interface DroneDispatcher {

    BlockPos dispatchPos();

    /** Owning side; null = unclaimed. {@link DroneDispatchers#permits}. */
    @Nullable
    UUID side();

    /** Kinds {@link #dispatch} would accept now, display order. */
    List<DroneTask.Kind> offers();

    /** One-step program {@code kind} at {@code at}; {@code kind} outside {@link #offers} => refusal. */
    DroneMission.Result dispatch(ServerPlayer by, DroneTask.Kind kind, Vec3 at);
}
