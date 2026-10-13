package com.wf.wflib.pda.drone;

import com.wf.wflib.config.WFConfig;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.drone.DroneRecall;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.dispatch.DroneDispatcher;
import com.wf.wflib.drone.dispatch.DroneDispatchers;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.pda.Pda;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Drones app, server end: the held PDA's drone channels (camera linker) with flight state; remote dispatch. */
public final class PdaDrones {
    /** = {@code maxBound} config ceiling. */
    static final int CAP = 32;

    private PdaDrones() {
    }

    /** Unloaded drones recalled (as the Cameras app). Gametest seam. */
    public static DroneStatusPacket status(ServerPlayer player) {
        CameraChannels channels = Pda.heldStack(player).getOrDefault(ModDataComponents.CHANNELS.get(),
                CameraChannels.EMPTY);
        List<DroneStatusPacket.Entry> out = new ArrayList<>();
        for (FeedTarget target : channels.targets()) {
            if (target.drone().isEmpty()) {
                continue;
            }
            UUID id = target.drone().get();
            if (player.serverLevel().getEntity(id) instanceof DroneEntity drone && drone.isAlive()) {
                DroneTask task = drone.orders().getProgram().current();
                out.add(new DroneStatusPacket.Entry(target.resolve(player.serverLevel()),
                        drone.callsign(), drone.flight().getDroneState().name(),
                        drone.battery().percent(), task == null ? "" : task.label(),
                        (int) player.distanceTo(drone)));
            } else {
                DroneRecall.recall(player.serverLevel(), id);
                out.add(new DroneStatusPacket.Entry(0, id.toString().substring(0, 8), "", 0, "", -1));
            }
        }
        return new DroneStatusPacket(out, Optional.ofNullable(DroneDispatchers.nearest(player)).map(d -> {
            List<String> offers = new ArrayList<>();
            for (DroneTask.Kind kind : d.offers()) {
                offers.add(kind.id());
            }
            return new DroneStatusPacket.Pad(d.dispatchPos(),
                    (int) Math.sqrt(d.dispatchPos().distToCenterSqr(player.position())), offers);
        }));
    }

    /** @return reply line; launched drones join the held PDA's channels. */
    public static String dispatch(ServerPlayer player, DroneTask.Kind kind, Vec3 at) {
        if (!Pda.held(player)) {
            return "err: no PDA in hand";
        }
        DroneDispatcher dispatcher = DroneDispatchers.nearest(player);
        if (dispatcher == null) {
            return "err: no drone pad in reach";
        }
        DroneMission.Result result = dispatcher.dispatch(player, kind, at);
        if (!result.ok()) {
            return "err: " + result.error();
        }
        ItemStack pda = Pda.heldStack(player);
        CameraChannels channels = pda.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
        int cap = WFConfig.CAMERA_MAX_CHANNELS.get();
        for (DroneEntity drone : result.drones()) {
            channels = channels.with(FeedTarget.ofDrone(drone.getUUID()), cap);
        }
        pda.set(ModDataComponents.CHANNELS.get(), channels);
        return String.format(Locale.ROOT, "%s x%d launched toward %d %d", kind.id(), result.ordered(), (int) at.x,
                (int) at.z);
    }
}
