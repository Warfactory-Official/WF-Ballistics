package com.wf.wflib.mixin.xaero.minimap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

@Mixin(value = MinimapFBORenderer.class, remap = false)
public interface AccessorMinimapFBORenderer {

    /** Null until the minimap's framebuffers first load; rebuilt on each reload. */
    @Accessor("minimapElementMapRendererHandler")
    MinimapElementMapRendererHandler wflib$inMapHandler();
}
