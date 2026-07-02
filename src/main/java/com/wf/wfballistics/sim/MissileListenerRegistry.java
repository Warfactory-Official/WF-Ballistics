package com.wf.wfballistics.sim;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.*;


public final class MissileListenerRegistry {
    private static final Map<ResourceKey<Level>, MissileListenerRegistry> BY_LEVEL = new HashMap<>();

    // Keyed by BlockPos (block entities) or UUID (entities) so a listener can refresh/remove itself.
    private final Map<Object, IMissileListener> listeners = new HashMap<>();

    public static MissileListenerRegistry get(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new MissileListenerRegistry());
    }

    /**
     * Drop a level's registry.
     */
    public static void clear(ServerLevel level) {
        BY_LEVEL.remove(level.dimension());
    }

    public void register(Object key, IMissileListener listener) {
        listeners.put(key, listener);
    }

    public void deregister(Object key) {
        listeners.remove(key);
    }

    /**
     * Snapshot of currently-valid listeners; purges any that have gone invalid.
     */
    public List<IMissileListener> valid() {
        List<IMissileListener> out = new ArrayList<>();
        Iterator<Map.Entry<Object, IMissileListener>> it = listeners.entrySet().iterator();
        while (it.hasNext()) {
            IMissileListener l = it.next().getValue();
            if (l.listenerValid()) {
                out.add(l);
            } else {
                it.remove();
            }
        }
        return out;
    }
}
