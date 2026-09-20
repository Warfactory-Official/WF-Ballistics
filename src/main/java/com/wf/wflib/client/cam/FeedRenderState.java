package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jetbrains.annotations.Nullable;

/**
 * The pieces of {@code LevelRenderer} state that are <em>per camera</em> rather than per world, exposed so a feed
 * can bring its own.
 */
public interface FeedRenderState {

    /**
     * @return the section grid, or null if this renderer has no world attached. Swapped for the feed's own
     *      during a pass, and also the identity a feed's grid is built alongside: the main one is rebuilt on
     *      render-distance and dimension changes, and both that and a graph holding sections from a released grid
     *      are a crash waiting for the next traversal.
     */
    @Nullable
    ViewArea wfCamViewArea();

    void wfCamSetViewArea(@Nullable ViewArea viewArea);

    /** {@code {lastCameraSectionX, Y, Z}}: the section the grid was last re-centred on. */
    int[] wfCamLastCameraSection();

    void wfCamSetLastCameraSection(int[] section);

    SectionOcclusionGraph wfCamGraph();

    void wfCamSetGraph(SectionOcclusionGraph graph);

    ObjectArrayList<SectionRenderDispatcher.RenderSection> wfCamVisibleSections();

    void wfCamSetVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

    /** {@code {prevCamX, prevCamY, prevCamZ, prevCamRotX, prevCamRotY}}: the invalidation thresholds. */
    double[] wfCamPrevCamera();

    void wfCamSetPrevCamera(double[] values);

    /**
     * {@code {xTransparentOld, yTransparentOld, zTransparentOld}}, where the translucent quads were last sorted
     * from.
     */
    double[] wfCamTransparentOrigin();

    void wfCamSetTransparentOrigin(double[] values);

    /**
     * Fabulous graphics' auxiliary framebuffers, in the order {@code {entity, translucent, itemEntity, particles,
     * weather, clouds}}.
     */
    RenderTarget[] wfCamFabulousTargets();

    void wfCamSetFabulousTargets(RenderTarget[] targets);

    @Nullable
    PostChain wfCamTransparencyChain();

    void wfCamSetTransparencyChain(@Nullable PostChain chain);
}
