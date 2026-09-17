package com.wf.wfballistics.client.cam;

import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.Method;

/** Tells Sodium about a chunk it will otherwise never draw. */
public final class SodiumChunkProbe {

    /** Every package this holder has lived in. */
    private static final String[] HOLDERS = {
            "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder",
            "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder",
            "org.embeddedt.embeddium.client.render.chunk.map.ChunkTrackerHolder",
            "org.embeddedt.embeddium.render.chunk.map.ChunkTrackerHolder"
    };

    /** {@code ChunkStatus.FLAG_HAS_BLOCK_DATA}. A constant rather than a lookup: it has been 1 throughout. */
    private static final int FLAG_HAS_BLOCK_DATA = 1;

    private static boolean resolved;
    private static Method holderGet;
    private static Method statusAdded;
    private static Method statusRemoved;

    private SodiumChunkProbe() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        for (String name : HOLDERS) {
            try {
                Class<?> holder = Class.forName(name, false, SodiumChunkProbe.class.getClassLoader());
                Method get = holder.getMethod("get", ClientLevel.class);
                Class<?> tracker = get.getReturnType();
                statusAdded = tracker.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
                statusRemoved = tracker.getMethod("onChunkStatusRemoved", int.class, int.class, int.class);
                holderGet = get;
                return;
            } catch (Throwable ignored) {
                holderGet = null;
                statusAdded = null;
                statusRemoved = null;
            }
        }
    }

    /** @return whether a Sodium-family chunk tracker is present and reachable. */
    public static boolean present() {
        resolve();
        return holderGet != null;
    }

    public static void announceLoaded(ClientLevel level, int chunkX, int chunkZ) {
        announce(level, chunkX, chunkZ, true);
    }

    public static void announceUnloaded(ClientLevel level, int chunkX, int chunkZ) {
        announce(level, chunkX, chunkZ, false);
    }

    private static void announce(ClientLevel level, int chunkX, int chunkZ, boolean loaded) {
        resolve();
        if (holderGet == null || level == null) {
            return;
        }
        try {
            Object tracker = holderGet.invoke(null, level);
            (loaded ? statusAdded : statusRemoved).invoke(tracker, chunkX, chunkZ, FLAG_HAS_BLOCK_DATA);
        } catch (Throwable ignored) {
        }
    }
}
