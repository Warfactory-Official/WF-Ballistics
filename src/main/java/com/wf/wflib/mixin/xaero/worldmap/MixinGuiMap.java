package com.wf.wflib.mixin.xaero.worldmap;

import com.wf.wflib.client.map.xaero.XaeroWorldMapHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/** Xaero's world map menu has no extension point. */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMap {

    @Shadow
    private int rightClickX;
    @Shadow
    private int rightClickY;
    @Shadow
    private int rightClickZ;

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), remap = false)
    private void wflib$toolItems(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        XaeroWorldMapHost.menu((GuiMap) (Object) this, cir.getReturnValue(), this.rightClickX, this.rightClickY,
                this.rightClickZ);
    }
}
