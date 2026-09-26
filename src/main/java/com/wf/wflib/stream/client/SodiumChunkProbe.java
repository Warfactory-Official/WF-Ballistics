package com.wf.wflib.stream.client;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Sodium adds a chunk only at the {@code onChunkLoaded} call inside vanilla's in-range branch of
 * {@code replaceWithPacketData}; an off-view chunk must be announced by hand. Removal hooks
 * {@code ClientLevel#unload} and the forget packet, both keyed by position.
 */
public final class SodiumChunkProbe {

    /** Every package this holder has lived in. */
    private static final String[] HOLDERS = {
            "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder",
            "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder",
            "org.embeddedt.embeddium.client.render.chunk.map.ChunkTrackerHolder",
            "org.embeddedt.embeddium.render.chunk.map.ChunkTrackerHolder"
    };

    /** {@code ChunkStatus.FLAG_HAS_BLOCK_DATA}; 1 in every release. Light (2) arrives with the light packet. */
    private static final int FLAG_HAS_BLOCK_DATA = 1;

    private static boolean resolved;
    private static Method holderGet;
    private static Method statusAdded;
    private static Method readyChunks;

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
                readyChunks = tracker.getMethod("getReadyChunks");
                holderGet = get;
                return;
            } catch (Throwable ignored) {
                holderGet = null;
                statusAdded = null;
                readyChunks = null;
            }
        }
    }

    public static boolean present() {
        resolve();
        return holderGet != null;
    }

    public static void announceLoaded(ClientLevel level, int chunkX, int chunkZ) {
        resolve();
        if (holderGet == null) {
            return;
        }
        try {
            statusAdded.invoke(holderGet.invoke(null, level), chunkX, chunkZ, FLAG_HAS_BLOCK_DATA);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return chunks Sodium will draw, or null without Sodium */
    @Nullable
    public static LongOpenHashSet readySet(ClientLevel level) {
        resolve();
        if (holderGet == null) {
            return null;
        }
        try {
            return new LongOpenHashSet((LongCollection) readyChunks.invoke(holderGet.invoke(null, level)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
