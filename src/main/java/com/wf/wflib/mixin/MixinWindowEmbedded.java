package com.wf.wflib.mixin;

import com.mojang.blaze3d.platform.Window;
import com.wf.wflib.client.screen.EmbeddedScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link EmbeddedScreen} running => window = its target (px, GUI scale, scaled size). */
@Mixin(Window.class)
public abstract class MixinWindowEmbedded {
    @Inject(method = {"getWidth", "getScreenWidth"}, at = @At("HEAD"), cancellable = true)
    private void wflib$width(CallbackInfoReturnable<Integer> cir) {
        EmbeddedScreen s = EmbeddedScreen.current();
        if (s != null) {
            cir.setReturnValue(s.pixelWidth());
        }
    }

    @Inject(method = {"getHeight", "getScreenHeight"}, at = @At("HEAD"), cancellable = true)
    private void wflib$height(CallbackInfoReturnable<Integer> cir) {
        EmbeddedScreen s = EmbeddedScreen.current();
        if (s != null) {
            cir.setReturnValue(s.pixelHeight());
        }
    }

    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    private void wflib$guiWidth(CallbackInfoReturnable<Integer> cir) {
        EmbeddedScreen s = EmbeddedScreen.current();
        if (s != null) {
            cir.setReturnValue(s.guiWidth());
        }
    }

    @Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
    private void wflib$guiHeight(CallbackInfoReturnable<Integer> cir) {
        EmbeddedScreen s = EmbeddedScreen.current();
        if (s != null) {
            cir.setReturnValue(s.guiHeight());
        }
    }

    @Inject(method = "getGuiScale", at = @At("HEAD"), cancellable = true)
    private void wflib$guiScale(CallbackInfoReturnable<Double> cir) {
        EmbeddedScreen s = EmbeddedScreen.current();
        if (s != null) {
            cir.setReturnValue((double) s.guiScale());
        }
    }
}
