package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** The level renderer state an offscreen view borrows for the length of its pass, and gives back afterwards. */
final class FeedRenderSwap implements AutoCloseable {

    /** {@link #viewDistance} of a view on the main grid. */
    static final int SHARED = -1;

    private static final RenderTarget[] NO_TARGETS = new RenderTarget[6];
    /** What {@code allChanged} sets, so the grid is repositioned on the first frame of a pass. */
    private static final int[] NEVER = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    private final int viewDistance;
    private final SectionOcclusionGraph graph = new SectionOcclusionGraph();
    private final CloudCache clouds = new CloudCache();
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
    private boolean gridInstalled;

    /** @param viewDistance sections either side of the camera for an own grid, or {@link #SHARED} */
    FeedRenderSwap(int viewDistance) {
        this.viewDistance = viewDistance;
    }

    boolean shared() {
        return this.viewDistance == SHARED;
    }

    int viewDistance() {
        return this.viewDistance;
    }

    /** @return true => this frame's {@code setupRender} will {@code allChanged}, replacing the grid mid-swap. */
    static boolean gridStale(LevelRenderer renderer) {
        return renderer instanceof FeedRenderState state
                && state.wfCamLastViewDistance() != Minecraft.getInstance().options.getEffectiveRenderDistance();
    }

    /**
     * Put this feed's state into the level renderer: always the Fabulous/outline targets + panoramic mode (else
     * their {@code clear} rebinds the window-sized viewport mid-pass), the grid only where the renderer keeps one.
     *
     * @return false if the grid was not swapped: world gone, or a third-party terrain renderer (Sodium: sections
     *      keyed by position, no distance bound, no second grid needed; it re-walks visibility per camera: cost,
     *      not a wrong picture).
     */
    boolean install(LevelRenderer renderer, Vec3 camera) {
        FeedTargets targets = (FeedTargets) renderer;
        Minecraft mc = Minecraft.getInstance();
        this.savedTargets = targets.wfCamTargets();
        this.savedTransparency = targets.wfCamTransparencyChain();
        this.savedPanoramic = mc.gameRenderer.isPanoramicMode();
        // Armed before the first write, so anything that throws mid-swap still gets put back.
        this.installed = true;
        targets.wfCamSetTargets(NO_TARGETS);
        targets.wfCamSetTransparencyChain(null);
        mc.gameRenderer.setPanoramicMode(true);

        if (!(renderer instanceof FeedRenderState state)) {
            return false;
        }
        ViewArea main = state.wfCamViewArea();
        if (main == null || mc.level == null) {
            return false;
        }
        if (main != this.builtWith || mc.level != this.builtFor) {
            releaseGrid();
            if (!shared()) {
                this.viewArea = new FeedViewArea(renderer.getSectionRenderDispatcher(), mc.level,
                        this.viewDistance, renderer);
            }
            this.builtWith = main;
            this.builtFor = mc.level;
            this.lastSection = NEVER.clone();
            this.graph.waitAndReset(shared() ? main : this.viewArea);
            FeedGraphs.track(this.graph);
        }
        if (shared()) {
            // Main sort kept: resort from here would reorder the main view's buffers.
            this.transparentOrigin = new double[]{camera.x, camera.y, camera.z};
        }

        this.savedGraph = state.wfCamGraph();
        this.savedVisible = state.wfCamVisibleSections();
        this.savedPrevCamera = state.wfCamPrevCamera();
        this.savedTransparentOrigin = state.wfCamTransparentOrigin();
        this.savedLastSection = state.wfCamLastCameraSection();
        this.savedViewArea = main;
        FeedGraphs.track(this.savedGraph);

        this.gridInstalled = true;
        if (!shared()) {
            state.wfCamSetViewArea(this.viewArea);
            state.wfCamSetLastCameraSection(this.lastSection);
        }
        state.wfCamSetGraph(this.graph);
        state.wfCamSetVisibleSections(this.visible);
        state.wfCamSetPrevCamera(this.prevCamera);
        state.wfCamSetTransparentOrigin(this.transparentOrigin);
        state.wfCamSwapClouds(this.clouds);
        return true;
    }

    void uninstall(LevelRenderer renderer) {
        if (!this.installed) {
            return;
        }
        this.installed = false;
        FeedTargets targets = (FeedTargets) renderer;
        targets.wfCamSetTargets(this.savedTargets);
        targets.wfCamSetTransparencyChain(this.savedTransparency);
        Minecraft.getInstance().gameRenderer.setPanoramicMode(this.savedPanoramic);
        if (!this.gridInstalled) {
            return;
        }
        this.gridInstalled = false;
        FeedRenderState state = (FeedRenderState) renderer;
        this.prevCamera = state.wfCamPrevCamera();
        this.visible = state.wfCamVisibleSections();
        this.transparentOrigin = state.wfCamTransparentOrigin();
        if (!shared()) {
            this.lastSection = state.wfCamLastCameraSection();
            state.wfCamSetViewArea(this.savedViewArea);
            state.wfCamSetLastCameraSection(this.savedLastSection);
        }
        state.wfCamSetGraph(this.savedGraph);
        state.wfCamSetVisibleSections(this.savedVisible);
        state.wfCamSetPrevCamera(this.savedPrevCamera);
        state.wfCamSetTransparentOrigin(this.savedTransparentOrigin);
        state.wfCamSwapClouds(this.clouds);
    }

    /** Reset the graph first, then give the sections back. */
    private void releaseGrid() {
        if (this.builtWith == null) {
            return;
        }
        this.graph.waitAndReset(null);
        if (this.viewArea != null) {
            this.viewArea.release();
            this.viewArea = null;
        }
        this.builtWith = null;
        this.builtFor = null;
    }

    @Override
    public void close() {
        FeedGraphs.forget(this.graph);
        releaseGrid();
        this.clouds.close();
        this.visible.clear();
    }
}
