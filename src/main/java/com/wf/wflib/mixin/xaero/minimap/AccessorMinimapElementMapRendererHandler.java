package com.wf.wflib.mixin.xaero.minimap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

/** Set by {@code prepareRender} before each minimap frame's elements. */
@Mixin(value = MinimapElementMapRendererHandler.class, remap = false)
public interface AccessorMinimapElementMapRendererHandler {

    @Accessor("ps")
    double wflib$ps();

    @Accessor("pc")
    double wflib$pc();

    /** Framebuffer px/block. */
    @Accessor("zoom")
    double wflib$zoom();

    @Accessor("halfWView")
    float wflib$halfView();
}
