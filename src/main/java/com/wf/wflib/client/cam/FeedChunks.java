package com.wf.wflib.client.cam;

import com.wf.wflib.network.CameraCapabilityPacket;
import com.wf.wflib.stream.client.OffViewChunks;
import com.wf.wflib.stream.client.SodiumChunkProbe;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** Whether to ask for drone terrain at all. Holding it is {@code MixinClientChunkCache}'s job. */
public final class FeedChunks {

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

    /** @return chunks held outside the ring, for {@code /wflib drone camera stats}. */
    public static int pinnedCount() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : ((OffViewChunks) mc.level.getChunkSource()).wfOffViewPositions().size();
    }
}
