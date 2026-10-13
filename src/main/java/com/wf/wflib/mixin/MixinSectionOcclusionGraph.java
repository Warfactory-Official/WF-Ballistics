package com.wf.wflib.mixin;

import com.wf.wflib.client.cam.FeedGraphColumns;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionOcclusionGraph.class)
public abstract class MixinSectionOcclusionGraph implements FeedGraphColumns {

    @Unique
    private volatile boolean wflib$columns;

    @Override
    public void wflib$setColumns(boolean columns) {
        this.wflib$columns = columns;
    }

    @Override
    public boolean wflib$columns() {
        return this.wflib$columns;
    }

    /** Only read there by the height cut. */
    @Redirect(method = "getRelativeFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ViewArea;getViewDistance()I"))
    private int wflib$anyHeight(ViewArea area) {
        return this.wflib$columns ? Integer.MAX_VALUE / 16 : area.getViewDistance();
    }
}
