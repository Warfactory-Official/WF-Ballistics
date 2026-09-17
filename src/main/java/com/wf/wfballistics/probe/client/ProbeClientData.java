package com.wf.wfballistics.probe.client;

import com.wf.wfballistics.network.WFNetwork;
import com.wf.wfballistics.probe.ProbeDataPacket;
import com.wf.wfballistics.probe.ProbeRequestPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** The client's copy of whatever the server last said about the thing being looked at. */
public final class ProbeClientData {

    private static final CompoundTag EMPTY = new CompoundTag();

    private static BlockPos pos = BlockPos.ZERO;
    private static int entityId = -1;
    private static CompoundTag data = EMPTY;
    private static boolean asked;
    private static long requestedAtTick;

    private ProbeClientData() {
    }

    /** @return what the server said about this target, or an empty tag */
    public static CompoundTag get(BlockPos target, int entity) {
        return sameTarget(target, entity) ? data : EMPTY;
    }

    /** Asks the server, if the target has changed or the answer has gone stale. */
    public static void request(BlockPos target, int entity, long gameTime, int refreshTicks) {
        if (!sameTarget(target, entity)) {
            pos = target.immutable();
            entityId = entity;
            data = EMPTY;
            asked = false;
        }
        if (asked && gameTime - requestedAtTick < refreshTicks) {
            return;
        }
        asked = true;
        requestedAtTick = gameTime;
        WFNetwork.sendToServer(new ProbeRequestPacket(pos, entityId));
    }

    /** Forgets the current answer; used when nothing is being looked at. */
    public static void clear() {
        entityId = -1;
        pos = BlockPos.ZERO;
        data = EMPTY;
        asked = false;
    }

    public static void accept(ProbeDataPacket packet) {
        if (sameTarget(packet.pos(), packet.entityId())) {
            data = packet.data();
        }
    }

    private static boolean sameTarget(BlockPos target, int entity) {
        return entity == entityId && (entity >= 0 || pos.equals(target));
    }
}
