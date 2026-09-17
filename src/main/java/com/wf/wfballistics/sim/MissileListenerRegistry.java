package com.wf.wfballistics.sim;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.chunk.WFChunkValidation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-dimension registry of things that want a chance to engage a passing missile. */
public final class MissileListenerRegistry extends SavedData {
    public static final String NAME = "wfballistics_missile_listeners";

    private static final long THREAT_TTL_TICKS = 60L;
    private static final double WAKE_MARGIN = MissileSimConfig.LISTENER_SPAWN_MARGIN;

    /** Registered on the mod bus by {@code WFServerEvents.ModBusEvents#onRegisterTicketControllers}. */
    public static final TicketController CHUNK_TICKET = new TicketController(
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "missile_listener"),
            WFChunkValidation::validateTickets);

    private final Map<BlockPos, BlockRecord> blockRecords = new HashMap<>();
    private final Map<UUID, IMissileListener> entityListeners = new HashMap<>();
    // BlockPos of block listeners we currently hold a wakeup chunk ticket for (transient).
    private final Set<BlockPos> forced = new HashSet<>();
    private boolean reconciled = false;

    public static MissileListenerRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MissileListenerRegistry::new,
                        (tag, reg) -> MissileListenerRegistry.load(tag)),
                NAME);
    }

    public static MissileListenerRegistry load(CompoundTag tag) {
        MissileListenerRegistry r = new MissileListenerRegistry();
        ListTag list = tag.getList("Blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            BlockPos pos = new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z"));
            Vec3 center = new Vec3(t.getDouble("CX"), t.getDouble("CY"), t.getDouble("CZ"));
            r.blockRecords.put(pos, new BlockRecord(pos, center, t.getDouble("Range")));
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BlockRecord r : blockRecords.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("X", r.pos.getX());
            t.putInt("Y", r.pos.getY());
            t.putInt("Z", r.pos.getZ());
            t.putDouble("CX", r.center.x);
            t.putDouble("CY", r.center.y);
            t.putDouble("CZ", r.center.z);
            t.putDouble("Range", r.range);
            list.add(t);
        }
        tag.put("Blocks", list);
        return tag;
    }

    /** Register or refresh a listener. */
    public void register(Object key, IMissileListener listener) {
        if (key instanceof BlockPos pos) {
            Vec3 center = listener.listenerCenter();
            double range = listener.listenerRange();
            BlockRecord existing = blockRecords.get(pos);
            if (existing == null || existing.range != range || !existing.center.equals(center)) {
                blockRecords.put(pos, new BlockRecord(pos, center, range));
                setDirty();
            }
        } else {
            entityListeners.put((UUID) key, listener);
        }
    }

    public void deregister(Object key) {
        if (key instanceof BlockPos pos) {
            if (blockRecords.remove(pos) != null) {
                setDirty();
            }
            // Any wakeup ticket still held for pos is released by tickWakeups' orphan sweep (which has the level).
        } else {
            entityListeners.remove(key);
        }
    }

    /**
     * Detection view of every active listener (block records, never dropped on unload, plus valid entity listeners)
     * as plain center/range pairs.
     */
    public List<ListenerView> views() {
        List<ListenerView> out = new ArrayList<>();
        for (BlockRecord r : blockRecords.values()) {
            out.add(new ListenerView(r.center, r.range));
        }
        Iterator<Map.Entry<UUID, IMissileListener>> it = entityListeners.entrySet().iterator();
        while (it.hasNext()) {
            IMissileListener l = it.next().getValue();
            if (l.listenerValid()) {
                out.add(new ListenerView(l.listenerCenter(), l.listenerRange()));
            } else {
                it.remove();
            }
        }
        return out;
    }

    /**
     * Note that a missile is at {@code threatPos} this tick: any block listener within range wakes (or stays awake)
     * for {@link #THREAT_TTL_TICKS}.
     */
    public void noteThreat(Vec3 threatPos, long now) {
        if (blockRecords.isEmpty()) {
            return;
        }
        for (BlockRecord r : blockRecords.values()) {
            double reach = r.range + WAKE_MARGIN;
            if (r.center.distanceToSqr(threatPos) <= reach * reach) {
                r.lastThreat = now;
            }
        }
    }

    /** Hold a ticking chunk ticket on every block listener with a live threat, and release the rest. */
    public void tickWakeups(ServerLevel level, long now) {
        if (!reconciled) {
            for (BlockRecord r : blockRecords.values()) {
                setForced(level, r.pos, false);
            }
            forced.clear();
            reconciled = true;
        }
        // Release tickets whose block listener was deregistered (broken) since last tick.
        Iterator<BlockPos> fit = forced.iterator();
        while (fit.hasNext()) {
            BlockPos p = fit.next();
            if (!blockRecords.containsKey(p)) {
                setForced(level, p, false);
                fit.remove();
            }
        }
        if (blockRecords.isEmpty()) {
            return;
        }
        for (BlockRecord r : new ArrayList<>(blockRecords.values())) {
            boolean want = (now - r.lastThreat) <= THREAT_TTL_TICKS;
            if (want) {
                if (forced.add(r.pos)) {
                    setForced(level, r.pos, true);
                }
                if (level.isLoaded(r.pos) && !(level.getBlockEntity(r.pos) instanceof IMissileListener)) {
                    // The block was removed (broken while unloaded, worldedit, ...) but its record lingered.
                    setForced(level, r.pos, false);
                    forced.remove(r.pos);
                    blockRecords.remove(r.pos);
                    setDirty();
                }
            } else if (forced.remove(r.pos)) {
                setForced(level, r.pos, false);
            }
        }
    }

    private static void setForced(ServerLevel level, BlockPos pos, boolean add) {
        ChunkPos cp = new ChunkPos(pos);
        CHUNK_TICKET.forceChunk(level, pos, cp.x, cp.z, add, true);
    }

    public record ListenerView(Vec3 center, double range) {
    }

    private static final class BlockRecord {
        private final BlockPos pos;
        private final Vec3 center;
        private final double range;
        private long lastThreat = Long.MIN_VALUE;

        private BlockRecord(BlockPos pos, Vec3 center, double range) {
            this.pos = pos;
            this.center = center;
            this.range = range;
        }
    }
}
