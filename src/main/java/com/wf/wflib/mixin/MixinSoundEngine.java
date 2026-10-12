package com.wf.wflib.mixin;

import com.wf.wflib.audio.client.AudioMixer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine {
    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Inject(method = "loadLibrary", at = @At("TAIL"))
    private void wflib$libraryLoaded(CallbackInfo ci) {
        AudioMixer.libraryLoaded();
    }

    @Inject(method = "tickNonPaused", at = @At("TAIL"))
    private void wflib$afterTick(CallbackInfo ci) {
        AudioMixer.afterTick(instanceToChannel);
    }
}
