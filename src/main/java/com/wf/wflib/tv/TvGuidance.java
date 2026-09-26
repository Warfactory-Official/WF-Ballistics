package com.wf.wflib.tv;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.ModEntities;
import com.wf.wflib.missile.MissileSeeker;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.drone.cam.CameraSpec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** TV rounds, player side: who may fly a round, connect, loss notice. Flight: {@link MissileSeeker}. */
public final class TvGuidance {

    private TvGuidance() {
    }

    /**
     * @return true if {@code player} launched this round: by hand ({@code controlId}), or from anything they ride,
     *      a UAV they fly remotely included.
     */
    public static boolean operates(ServerPlayer player, MissileEntity missile) {
        UUID control = missile.getControlId();
        Entity owner = missile.getOwner();
        if (player.getUUID().equals(control) || owner == player) {
            return true;
        }
        for (Entity ride = player.getVehicle(); ride != null; ride = ride.getVehicle()) {
            if (ride.getUUID().equals(control) || ride == owner) {
                return true;
            }
        }
        return false;
    }

    /** {@link CameraNet} subscription gate for a TV round's feed. */
    public static boolean entitled(ServerLevel level, ServerPlayer player, int feedId) {
        return level.getEntity(feedId) instanceof MissileEntity missile && missile.seeker().spec() != null
                && operates(player, missile) && linked(player, missile);
    }

    private static boolean linked(ServerPlayer player, MissileEntity missile) {
        CameraSpec spec = missile.seeker().spec();
        return spec != null
                && spec.linkAt(player.getEyePosition().distanceTo(missile.position())) > CameraFeed.USABLE_LINK;
    }

    /** @return unit line of sight the operator is ordering, or null with nobody (entitled, in range) on the stick. */
    @Nullable
    public static Vec3 order(ServerLevel level, MissileEntity missile) {
        CameraNet.Order order = CameraNet.order(level, missile.getId());
        if (order == null || !operates(order.player(), missile) || !linked(order.player(), missile)) {
            return null;
        }
        return MissileSeeker.sight(order.yaw(), order.pitch());
    }

    /**
     * @return entity id of the newest round {@code player} may fly and is in datalink range of; 0 for none.
     *      Newest: the one just fired is the one being asked for.
     */
    public static int connect(ServerPlayer player) {
        MissileEntity best = null;
        for (MissileEntity missile : player.serverLevel().getEntities(ModEntities.STEALTH_MISSILE.get(),
                m -> m.seeker().spec() != null && m.isAlive() && !m.damage().isDowned() && !m.isDud())) {
            if (operates(player, missile) && linked(player, missile)
                    && (best == null || missile.tickCount < best.tickCount)) {
                best = missile;
            }
        }
        return best == null ? 0 : best.getId();
    }

    /** Round gone: tell whoever is flying it, instead of letting their feed go stale. */
    public static void lost(ServerLevel level, MissileEntity missile) {
        TvLinkPacket packet = new TvLinkPacket(missile.getId(), true);
        for (ServerPlayer player : CameraNet.subscribers(level, missile.getId())) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }
}
