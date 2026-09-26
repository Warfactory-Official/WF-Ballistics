package com.wf.wflib.rail.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The track of one route, laid a piece at a time.
 *
 * <p>Two implementations, and the difference between them is not a detail. {@link VanillaTrackLine}
 * writes rail blocks, one per block of route, each connected to its neighbours by a shape. The IR one
 * builds cubic curves into Immersive Railroading's own track graph, because IR track is not a block:
 * stock is positioned along the graph, and anything placed as blocks would look like track and be
 * unrunnable. Which one a world gets is decided by whether IR is installed, once, by
 * {@link TrackLines}.</p>
 *
 * <p>Chainage here is always <b>along the corridor</b>, the same measure the machine driving the face
 * uses, so a caller never has to know that the corridor and the surveyed centreline are different
 * lengths. Converting is each implementation's own business.</p>
 */
public interface TrackLine {

    /**
     * Lay everything the face has now cleared.
     *
     * @param chainage how far along the corridor the tunnel is finished
     * @return how many pieces were laid by this call
     */
    int layTo(ServerLevel level, double chainage);

    /** Lay all of it, whatever the face has reached. For a line that is finished. */
    default int layAll(ServerLevel level) {
        return layTo(level, Double.MAX_VALUE);
    }

    /**
     * Move the cursor past track that is already on the ground, building none of it.
     *
     * <p>What a machine resuming a half built route does before it starts. Laying it again would not
     * merely be wasted work: Immersive Railroading anchors a piece on one block and refuses a second
     * anchor in it, so the piece that is already there is the one that would be refused, and a route
     * would come back from a resupply trip with a hole in the line it had just laid.</p>
     *
     * @return how many pieces were taken as already built
     */
    int skipTo(double chainage);

    /** @return pieces laid so far. */
    int laid();

    /** @return pieces the whole route will take. */
    int total();

    /** @return what kind of railway this is, for a player reading a progress line. */
    String kind();

    /** @return where the railhead has got to. */
    BlockPos railhead();

    /** @return a phrase describing what has been built, to follow "laid ". */
    String describe();
}
