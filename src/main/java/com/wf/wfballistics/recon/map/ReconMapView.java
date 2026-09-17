package com.wf.wfballistics.recon.map;

import com.wf.wfballistics.recon.Band;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** One network's shape, flattened into something a client can be handed and a map can draw. */
public record ReconMapView(long netId, List<Node> nodes, List<Footprint> coverage) {

    /** What a client holds before it has been told anything, and after it has been told to stop. */
    public static final ReconMapView NONE = new ReconMapView(0L, List.of(), List.of());

    /** Nodes and footprints a single view may carry. */
    public static final int MAX_NODES = 256;
    public static final int MAX_FOOTPRINTS = 256;

    /** A net that exists as a subscription but has nothing on it. */
    public static ReconMapView empty(long netId) {
        return new ReconMapView(netId, List.of(), List.of());
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && coverage.isEmpty();
    }

    /**
     * @param label what the node calls itself: {@code hub}, or the probe kind.
     * @param root true for a hub: somewhere data is trying to reach.
     * @param hops links back to a hub, {@code 0} for a hub itself, {@code -1} for no route at all.
     * @param downstream how many nodes route through this one. The number that says which block to break.
     * @param uplink the node this one reaches the hub through, as a packed {@link BlockPos#asLong()}, or
     *      {@code 0} for a root or an orphan. Packed rather than an index into {@link #nodes}
     *      because the list is capped and an index would quietly point at the wrong node once it
     *      truncated.
     * @param linkRange blocks this node's radio spans in one hop, subject to clearance. Sent because a drawn
     *      link says two nodes talk and says nothing about how much further either could have
     *      reached, which is the question anyone deciding where the next probe goes is asking.
     */
    public record Node(BlockPos pos, String label, boolean root, int hops, int downstream, long uplink,
                       double linkRange) {

        public boolean online() {
            return hops >= 0;
        }
    }

    /**
     * @param band the band this set works in, or null for a met station, which detects nothing and whose
     *      range means "how far this sample describes the air", a different thing worth drawing
     *      differently.
     * @param range blocks, nominal. See the class note.
     * @param hops the grid cost of getting this sensor's data home, or {@code -1} for a sensor whose network
     *      is currently throwing its plots away. Off-grid sets (turrets) report {@code 0}.
     */
    public record Footprint(BlockPos pos, @Nullable Band band, double range, int hops) {

        public boolean online() {
            return hops >= 0;
        }
    }
}
