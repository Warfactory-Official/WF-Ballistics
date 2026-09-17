package com.wf.wfballistics.client.recon;

import com.wf.wfballistics.recon.map.ReconMapView;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** The last grid the server told this client about, and the seam a map plugin attaches to. */
public final class ReconMapClient {

    /** What a renderer has to be told. Implemented by the JourneyMap plugin, and by nothing else today. */
    public interface Listener {

        /**
         * @param dimension where these overlays belong. Carried explicitly rather than read from the client
         *      level, because a payload can arrive across a dimension change.
         */
        void onReconMap(ResourceKey<Level> dimension, ReconMapView view);
    }

    private static ReconMapView view = ReconMapView.NONE;
    @Nullable
    private static ResourceKey<Level> dimension;
    @Nullable
    private static Listener listener;

    private ReconMapClient() {
    }

    /** Attach the renderer, and replay what has already arrived. */
    public static void setListener(@Nullable Listener attached) {
        listener = attached;
        if (attached != null && dimension != null) {
            attached.onReconMap(dimension, view);
        }
    }

    public static void accept(ResourceKey<Level> dim, ReconMapView incoming) {
        dimension = dim;
        view = incoming;
        if (listener != null) {
            listener.onReconMap(dim, incoming);
        }
    }

    /** Forget everything on leaving a world. */
    public static void clear() {
        view = ReconMapView.NONE;
        if (listener != null && dimension != null) {
            listener.onReconMap(dimension, ReconMapView.NONE);
        }
        dimension = null;
    }

    public static ReconMapView view() {
        return view;
    }
}
