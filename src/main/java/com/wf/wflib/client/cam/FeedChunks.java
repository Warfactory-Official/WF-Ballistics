package com.wf.wflib.client.cam;

import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.network.CameraCapabilityPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client half of drone terrain streaming: what to keep, when to let it go, and whether to ask for it at all.
 */
public final class FeedChunks {

    /** Chunks either side of a drone within which a streamed chunk is accepted and kept. */
    private static final int KEEP_RADIUS = 18;
    /** A ceiling on how much terrain may be held this way, in chunks. */
    private static final int MAX_PINNED = 4096;
    /** Ticks between sweeps. The server forgets what it drops, so this is only ever catching up. */
    private static final int SWEEP_INTERVAL = 20;

    private static int ticks;

    private FeedChunks() {
    }

    /**
     * @return whether this client can render terrain outside its own view at all. Told to the server so it
     *      does not stream what cannot be drawn.
     */
    public static boolean capable() {
        return Minecraft.getInstance().levelRenderer instanceof FeedRenderState || SodiumChunkProbe.present();
    }

    /** Announce {@link #capable()} once, at login. */
    public static void announce() {
        PacketDistributor.sendToServer(new CameraCapabilityPacket(capable()));
    }

    /**
     * @param pinned how many chunks are already held this way; pass zero when asking about one already held,
     *      since the cap decides what to accept and never what to discard
     */
    public static boolean wanted(int chunkX, int chunkZ, int pinned) {
        if (pinned >= MAX_PINNED || !capable()) {
            return false;
        }
        for (CameraFeed feed : CameraFeedCache.feeds()) {
            if (!CameraFeedCache.live(feed.feedId())) {
                continue;
            }
            int dx = chunkX - SectionPos.blockToSectionCoord(Mth.floor(feed.x()));
            int dz = chunkZ - SectionPos.blockToSectionCoord(Mth.floor(feed.z()));
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= KEEP_RADIUS) {
                return true;
            }
        }
        return false;
    }

    public static void tick() {
        if (++ticks % SWEEP_INTERVAL != 0) {
            return;
        }
        FeedChunkStore store = store();
        if (store != null) {
            store.wfCamSweepPins();
        }
    }

    public static void clear() {
        FeedChunkStore store = store();
        if (store != null) {
            store.wfCamClearPins();
        }
    }

    /** @return chunks currently held for feeds, for {@code /wflib drone camera stats}. */
    public static int pinnedCount() {
        FeedChunkStore store = store();
        return store == null ? 0 : store.wfCamPinnedCount();
    }

    private static FeedChunkStore store() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        ChunkSource source = mc.level.getChunkSource();
        return source instanceof FeedChunkStore store ? store : null;
    }
}
