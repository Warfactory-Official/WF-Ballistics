package com.wf.wfballistics.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import com.wf.wfballistics.client.cam.FeedPass;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sorts a section's translucent geometry from the camera that asked for the sort, rather than from whichever camera
 * happens to be current when a background thread gets round to it.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class MixinRenderSectionSort {

    @Shadow
    @Final
    BlockPos.MutableBlockPos origin;

    /** Where this section's owning camera was when its pending sort or rebuild was scheduled. */
    @Unique
    private volatile Vec3 wfCam$sortOrigin;

    @Inject(method = "resortTransparency", at = @At("HEAD"))
    private void wfCamStampResort(RenderType renderType, SectionRenderDispatcher dispatcher,
                                  CallbackInfoReturnable<Boolean> cir) {
        this.wfCam$sortOrigin = FeedPass.sortOrigin();
    }

    /** The compile path sorts too. */
    @Inject(method = "createCompileTask", at = @At("HEAD"))
    private void wfCamStampCompile(RenderRegionCache regionCache, CallbackInfoReturnable<?> cir) {
        this.wfCam$sortOrigin = FeedPass.sortOrigin();
    }

    @Inject(method = "createVertexSorting", at = @At("HEAD"), cancellable = true)
    private void wfCamSortFromOwner(CallbackInfoReturnable<VertexSorting> cir) {
        Vec3 at = this.wfCam$sortOrigin;
        if (at != null) {
            cir.setReturnValue(VertexSorting.byDistance(
                    (float) (at.x - (double) this.origin.getX()),
                    (float) (at.y - (double) this.origin.getY()),
                    (float) (at.z - (double) this.origin.getZ())));
        }
    }
}
