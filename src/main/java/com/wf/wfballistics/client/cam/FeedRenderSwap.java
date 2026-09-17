package com.wf.wfballistics.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wf.wfballistics.config.WFClientConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jetbrains.annotations.Nullable;

/** The level renderer state a drone feed borrows for the length of its pass, and gives back afterwards. */
final class FeedRenderSwap implements AutoCloseable {

    /** {entityTarget, translucentTarget, itemEntityTarget, particlesTarget, weatherTarget, cloudsTarget} */
    private static final RenderTarget[] NO_TARGETS = new RenderTarget[6];
    /** What {@code allChanged} sets, so the grid is repositioned on the first frame of a pass. */
    private static final int[] NEVER = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    private final SectionOcclusionGraph graph = new SectionOcclusionGraph();
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = new ObjectArrayList<>(4096);
    /** Thresholds this feed's camera is measured against, in the same units {@code setupRender} uses. */
    private double[] prevCamera = {Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE,
            Double.MIN_VALUE, Double.MIN_VALUE};
    private int[] lastSection = NEVER.clone();
    /** Where this feed last sorted its translucent geometry from. */
    private double[] transparentOrigin = {0.0, 0.0, 0.0};

    @Nullable
    private FeedViewArea viewArea;
    /** The main grid this feed's was built alongside; its replacement means the world changed under us. */
    @Nullable
    private ViewArea builtWith;
    @Nullable
    private ClientLevel builtFor;
    private int builtAt;

    private SectionOcclusionGraph savedGraph;
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> savedVisible;
    private double[] savedPrevCamera;
    private double[] savedTransparentOrigin;
    private int[] savedLastSection;
    private ViewArea savedViewArea;
    private RenderTarget[] savedTargets;
    @Nullable
    private PostChain savedTransparency;
    private boolean savedPanoramic;
    private boolean installed;

    /**
     * Put this feed's state into the level renderer.
     *
     * @return false if this renderer keeps no shareable per-camera state: either the world is gone, or a
     *      third-party terrain renderer is installed and the mixin that exposes it deliberately was not. Under
     *      Sodium that is the whole of it and the feed still works: its sections are a position-keyed map with no
     *      distance bound, so there is no second grid to build and nothing to swap: only its chunk tracker to be
     *      told that a streamed chunk exists, which {@code SodiumChunkProbe} does. It has no Fabulous path either.
     *      What it does keep is {@code lastCameraPos} and a single {@code needsGraphUpdate} flag, so two cameras
     *      make each other re-walk visibility every pass: a cost, not a wrong picture, because that walk is
     *      synchronous and there is no half-finished answer for either view to inherit.
     */
    boolean install(LevelRenderer renderer) {
        if (!(renderer instanceof FeedRenderState state)) {
            return false;
        }
        ViewArea main = state.wfCamViewArea();
        Minecraft mc = Minecraft.getInstance();
        if (main == null || mc.level == null) {
            return false;
        }
        int distance = WFClientConfig.CAMERA_FEED_VIEW_DISTANCE.get();
        if (this.viewArea == null || main != this.builtWith || mc.level != this.builtFor
                || distance != this.builtAt) {
            releaseGrid();
            this.viewArea = new FeedViewArea(renderer.getSectionRenderDispatcher(), mc.level, distance, renderer);
            this.builtWith = main;
            this.builtFor = mc.level;
            this.builtAt = distance;
            this.lastSection = NEVER.clone();
            this.graph.waitAndReset(this.viewArea);
            FeedGraphs.track(this.graph);
        }

        this.savedGraph = state.wfCamGraph();
        this.savedVisible = state.wfCamVisibleSections();
        this.savedPrevCamera = state.wfCamPrevCamera();
        this.savedTransparentOrigin = state.wfCamTransparentOrigin();
        this.savedLastSection = state.wfCamLastCameraSection();
        this.savedViewArea = main;
        this.savedTargets = state.wfCamFabulousTargets();
        this.savedTransparency = state.wfCamTransparencyChain();
        this.savedPanoramic = mc.gameRenderer.isPanoramicMode();
        FeedGraphs.track(this.savedGraph);

        // Armed before the first write, so anything that throws mid-swap still gets put back.
        this.installed = true;
        state.wfCamSetViewArea(this.viewArea);
        state.wfCamSetGraph(this.graph);
        state.wfCamSetVisibleSections(this.visible);
        state.wfCamSetPrevCamera(this.prevCamera);
        state.wfCamSetTransparentOrigin(this.transparentOrigin);
        state.wfCamSetLastCameraSection(this.lastSection);
        state.wfCamSetFabulousTargets(NO_TARGETS);
        state.wfCamSetTransparencyChain(null);
        mc.gameRenderer.setPanoramicMode(true);
        return true;
    }

    void uninstall(LevelRenderer renderer) {
        if (!this.installed || !(renderer instanceof FeedRenderState state)) {
            return;
        }
        this.installed = false;
        this.prevCamera = state.wfCamPrevCamera();
        this.visible = state.wfCamVisibleSections();
        this.lastSection = state.wfCamLastCameraSection();
        this.transparentOrigin = state.wfCamTransparentOrigin();
        state.wfCamSetViewArea(this.savedViewArea);
        state.wfCamSetGraph(this.savedGraph);
        state.wfCamSetVisibleSections(this.savedVisible);
        state.wfCamSetPrevCamera(this.savedPrevCamera);
        state.wfCamSetTransparentOrigin(this.savedTransparentOrigin);
        state.wfCamSetLastCameraSection(this.savedLastSection);
        state.wfCamSetFabulousTargets(this.savedTargets);
        state.wfCamSetTransparencyChain(this.savedTransparency);
        Minecraft.getInstance().gameRenderer.setPanoramicMode(this.savedPanoramic);
    }

    /** Reset the graph first, then give the sections back. */
    private void releaseGrid() {
        if (this.viewArea == null) {
            return;
        }
        this.graph.waitAndReset(null);
        this.viewArea.release();
        this.viewArea = null;
        this.builtWith = null;
        this.builtFor = null;
    }

    @Override
    public void close() {
        FeedGraphs.forget(this.graph);
        releaseGrid();
        this.visible.clear();
    }
}
