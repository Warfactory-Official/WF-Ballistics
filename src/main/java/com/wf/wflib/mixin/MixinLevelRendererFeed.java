package com.wf.wflib.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.wf.wflib.client.cam.CloudCache;
import com.wf.wflib.client.cam.FeedGraphs;
import com.wf.wflib.client.cam.FeedPass;
import com.wf.wflib.client.cam.FeedRenderState;
import com.wf.wflib.client.cam.FeedViewArea;
import com.wf.wflib.stream.client.DetachedView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
    private int lastViewDistance;

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
    private boolean generateClouds;
    @Shadow
    private VertexBuffer cloudBuffer;
    @Shadow
    private int prevCloudX;
    @Shadow
    private int prevCloudY;
    @Shadow
    private int prevCloudZ;
    @Shadow
    private Vec3 prevCloudColor;
    @Shadow
    private CloudStatus prevCloudsType;

    @Inject(method = {"needsUpdate", "allChanged"}, at = @At("TAIL"))
    private void wfCamRegenerateClouds(CallbackInfo ci) {
        CloudCache.regenerateAll();
    }

    @Override
    public ViewArea wfCamViewArea() {
        return this.viewArea;
    }

    @Override
    public void wfCamSetViewArea(ViewArea viewArea) {
        this.viewArea = viewArea;
    }

    @Override
    public int wfCamLastViewDistance() {
        return this.lastViewDistance;
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
    public void wfCamSwapClouds(CloudCache cache) {
        boolean generate = this.generateClouds;
        VertexBuffer buffer = this.cloudBuffer;
        int x = this.prevCloudX;
        int y = this.prevCloudY;
        int z = this.prevCloudZ;
        Vec3 color = this.prevCloudColor;
        CloudStatus type = this.prevCloudsType;
        this.generateClouds = cache.generate;
        this.cloudBuffer = cache.buffer;
        this.prevCloudX = cache.x;
        this.prevCloudY = cache.y;
        this.prevCloudZ = cache.z;
        this.prevCloudColor = cache.color;
        this.prevCloudsType = cache.type;
        cache.generate = generate;
        cache.buffer = buffer;
        cache.x = x;
        cache.y = y;
        cache.z = z;
        cache.color = color;
        cache.type = type;
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

    @Unique
    private Vec3 wfGridOrigin;

    /** Grid origin: feed pass camera, else remote operator's host, else the player. */
    @Inject(method = "setupRender", at = @At("HEAD"))
    private void wfResolveGridOrigin(CallbackInfo ci) {
        Vec3 origin = FeedPass.origin();
        if (origin == null) {
            Entity host = DetachedView.host();
            origin = host == null ? null : host.position();
        }
        this.wfGridOrigin = origin;
    }

    @Redirect(method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private double wfGridOriginX(LocalPlayer player) {
        return this.wfGridOrigin == null ? player.getX() : this.wfGridOrigin.x;
    }

    @Redirect(method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    private double wfGridOriginY(LocalPlayer player) {
        return this.wfGridOrigin == null ? player.getY() : this.wfGridOrigin.y;
    }

    @Redirect(method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private double wfGridOriginZ(LocalPlayer player) {
        return this.wfGridOrigin == null ? player.getZ() : this.wfGridOrigin.z;
    }
}
