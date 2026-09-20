package com.wf.wflib.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wf.wflib.client.cam.FeedGraphs;
import com.wf.wflib.client.cam.FeedPass;
import com.wf.wflib.client.cam.FeedRenderState;
import com.wf.wflib.client.cam.FeedViewArea;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exposes the level renderer's per-camera state so a drone feed can swap in its own for the length of its pass, and
 * fans the two graph events out to every graph in play.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererFeed implements FeedRenderState {

    @Shadow
    @Final
    @Mutable
    private SectionOcclusionGraph sectionOcclusionGraph;

    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    private ViewArea viewArea;

    @Shadow
    private int lastCameraSectionX;
    @Shadow
    private int lastCameraSectionY;
    @Shadow
    private int lastCameraSectionZ;

    @Shadow
    private double prevCamX;
    @Shadow
    private double prevCamY;
    @Shadow
    private double prevCamZ;
    @Shadow
    private double prevCamRotX;
    @Shadow
    private double prevCamRotY;

    @Shadow
    private double xTransparentOld;
    @Shadow
    private double yTransparentOld;
    @Shadow
    private double zTransparentOld;

    @Shadow
    private RenderTarget entityTarget;
    @Shadow
    private RenderTarget translucentTarget;
    @Shadow
    private RenderTarget itemEntityTarget;
    @Shadow
    private RenderTarget particlesTarget;
    @Shadow
    private RenderTarget weatherTarget;
    @Shadow
    private RenderTarget cloudsTarget;
    @Shadow
    private PostChain transparencyChain;

    @Override
    public ViewArea wfCamViewArea() {
        return this.viewArea;
    }

    @Override
    public void wfCamSetViewArea(ViewArea viewArea) {
        this.viewArea = viewArea;
    }

    @Override
    public int[] wfCamLastCameraSection() {
        return new int[]{this.lastCameraSectionX, this.lastCameraSectionY, this.lastCameraSectionZ};
    }

    @Override
    public void wfCamSetLastCameraSection(int[] section) {
        this.lastCameraSectionX = section[0];
        this.lastCameraSectionY = section[1];
        this.lastCameraSectionZ = section[2];
    }

    @Override
    public SectionOcclusionGraph wfCamGraph() {
        return this.sectionOcclusionGraph;
    }

    @Override
    public void wfCamSetGraph(SectionOcclusionGraph graph) {
        this.sectionOcclusionGraph = graph;
    }

    @Override
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> wfCamVisibleSections() {
        return this.visibleSections;
    }

    @Override
    public void wfCamSetVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections) {
        this.visibleSections = sections;
    }

    @Override
    public double[] wfCamPrevCamera() {
        return new double[]{this.prevCamX, this.prevCamY, this.prevCamZ, this.prevCamRotX, this.prevCamRotY};
    }

    @Override
    public void wfCamSetPrevCamera(double[] values) {
        this.prevCamX = values[0];
        this.prevCamY = values[1];
        this.prevCamZ = values[2];
        this.prevCamRotX = values[3];
        this.prevCamRotY = values[4];
    }

    @Override
    public double[] wfCamTransparentOrigin() {
        return new double[]{this.xTransparentOld, this.yTransparentOld, this.zTransparentOld};
    }

    @Override
    public void wfCamSetTransparentOrigin(double[] values) {
        this.xTransparentOld = values[0];
        this.yTransparentOld = values[1];
        this.zTransparentOld = values[2];
    }

    @Override
    public RenderTarget[] wfCamFabulousTargets() {
        return new RenderTarget[]{this.entityTarget, this.translucentTarget, this.itemEntityTarget,
                this.particlesTarget, this.weatherTarget, this.cloudsTarget};
    }

    @Override
    public void wfCamSetFabulousTargets(RenderTarget[] targets) {
        this.entityTarget = targets[0];
        this.translucentTarget = targets[1];
        this.itemEntityTarget = targets[2];
        this.particlesTarget = targets[3];
        this.weatherTarget = targets[4];
        this.cloudsTarget = targets[5];
    }

    @Override
    public PostChain wfCamTransparencyChain() {
        return this.transparencyChain;
    }

    @Override
    public void wfCamSetTransparencyChain(PostChain chain) {
        this.transparencyChain = chain;
    }

    @Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
    private void wfCamShareCompiledSection(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        FeedGraphs.onSectionCompiled(this.sectionOcclusionGraph, section);
    }

    @Inject(method = "onChunkLoaded", at = @At("HEAD"))
    private void wfCamShareLoadedChunk(ChunkPos pos, CallbackInfo ci) {
        FeedGraphs.onChunkLoaded(this.sectionOcclusionGraph, pos);
    }

    /** Announce a changed section to every feed grid as well as the main one. */
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void wfCamShareSectionDirty(int sectionX, int sectionY, int sectionZ,
                                        boolean reRenderOnMainThread, CallbackInfo ci) {
        FeedViewArea.dirtyAll(sectionX, sectionY, sectionZ, reRenderOnMainThread);
    }

    @Redirect(method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private double wfCamGridOriginX(LocalPlayer player) {
        Vec3 origin = FeedPass.origin();
        return origin == null ? player.getX() : origin.x;
    }

    @Redirect(method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    private double wfCamGridOriginY(LocalPlayer player) {
        Vec3 origin = FeedPass.origin();
        return origin == null ? player.getY() : origin.y;
    }

    @Redirect(method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private double wfCamGridOriginZ(LocalPlayer player) {
        Vec3 origin = FeedPass.origin();
        return origin == null ? player.getZ() : origin.z;
    }
}
