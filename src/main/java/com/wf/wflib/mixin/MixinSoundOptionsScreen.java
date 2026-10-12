package com.wf.wflib.mixin;

import com.wf.wflib.audio.client.AudioOptions;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(SoundOptionsScreen.class)
public abstract class MixinSoundOptionsScreen {
    @Inject(method = "getAllSoundOptionsExceptMaster", at = @At("RETURN"), cancellable = true)
    private void wflib$categories(CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] base = cir.getReturnValue();
        OptionInstance<?>[] ours = AudioOptions.sliders();
        OptionInstance<?>[] all = Arrays.copyOf(base, base.length + ours.length);
        System.arraycopy(ours, 0, all, base.length, ours.length);
        cir.setReturnValue(all);
    }
}
