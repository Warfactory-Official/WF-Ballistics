package com.wf.wfballistics.sim;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-dimension registry of things that want a chance to engage a passing missile. Two kinds of listener:
 *
 * <ul>
 *   <li><b>Block listeners</b> (turret batteries, CIWS, the debug block) are keyed by {@link BlockPos} and kept
 *       as persistent records ({@link SavedData}). They survive chunk unload and a full server restart, so a
 *       missile crossing an unmanned base's defenses is still seen. When a missile closes on such a listener
 *       whose chunk is unloaded, {@link #tickWakeups} force-loads the block (ticking) so the real block entity
 *       wakes, tracks, and fires; the ticket is dropped once the threat clears.</li>
 *   <li><b>Entity listeners</b> (in-world interceptor missiles) are keyed by {@link UUID} and held transiently as
 *       live references, purged when they go invalid. They are never force-loaded (they self-load or offload).</li>
 * </ul>
 */
public final class MissileListenerRegistry extends SavedData {
    public static final String NAME = "wfballistics_missile_listeners";

    // How long after a missile was last seen within a block listener's range the wakeup ticket is held, so a
    // brief gap between threat reports doesn't unload-and-reload the turret repeatedly.
    private static final long THREAT_TTL_TICKS = 60L;
    // Extra reach beyond the raw detection range at which a threat starts waking the block, giving the block
    // entity a couple of ticks to load and start tracking before the missile is in firing range.
    private static final double WAKE_MARGIN = MissileSimConfig.LISTENER_SPAWN_MARGIN;

    private final Map<BlockPos, BlockRecord> blockRecords = new HashMap<>();
    private final Map<UUID, IMissileListener> entityListeners = new HashMap<>();
    // BlockPos of block listeners we currently hold a wakeup chunk ticket for (transient).
    private final Set<BlockPos> forced = new HashSet<>();
    // On the first wakeup tick after (re)load, release any wakeup tickets left over from a previous session
    // before re-deriving them from live threats, so a ticket can't leak across a restart.
    private boolean reconciled = false;

    public static MissileListenerRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(MissileListenerRegistry::load, MissileListenerRegistry::new, NAME);
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
    public CompoundTag save(CompoundTag tag) {
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

    /**
     * Register or refresh a listener. A {@link BlockPos} key is a persistent block listener; any other key
     * (a {@link UUID}) is a transient entity listener.
     */
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
     * Detection view of every active listener (block records, never dropped on unload, plus valid entity
     * listeners) as plain center/range pairs.
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
     * Note that a missile is at {@code threatPos} this tick: any block listener within range wakes (or stays
     * awake) for {@link #THREAT_TTL_TICKS}. Cheap distance checks over the (small) record set; safe to call
     * every tick from every missile.
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

    /**
     * Hold a ticking chunk ticket on every block listener with a live threat, and release the rest. Also
     * self-heals records whose block is gone. Runs once per dimension per tick.
     */
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
        ForgeChunkManager.forceChunk(level, WFBallistics.MODID, pos, cp.x, cp.z, add, true);
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
