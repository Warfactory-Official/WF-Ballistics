package com.wf.wfballistics.mixin;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes a section lookup outside the grid a miss instead of a wrap. */
@Mixin(ViewArea.class)
public abstract class MixinViewArea {

    @Inject(method = "getRenderSectionAt", at = @At("RETURN"), cancellable = true)
    private void wfCamRejectWrappedSection(BlockPos pos,
                                           CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> cir) {
        SectionRenderDispatcher.RenderSection section = cir.getReturnValue();
        if (section == null) {
            return;
        }
        BlockPos origin = section.getOrigin();
        if (origin.getX() != Mth.floorDiv(pos.getX(), 16) * 16
                || origin.getZ() != Mth.floorDiv(pos.getZ(), 16) * 16) {
            cir.setReturnValue(null);
        }
    }
}
