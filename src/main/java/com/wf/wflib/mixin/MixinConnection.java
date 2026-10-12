package com.wf.wflib.mixin;

import com.wf.wflib.stream.SharedChunkPackets;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class MixinConnection {

    @Shadow
    private Channel channel;

    @Inject(method = "setupCompression", at = @At("TAIL"))
    private void wfSharedChunkHandler(int threshold, boolean validate, CallbackInfo ci) {
        SharedChunkPackets.configure(this.channel.pipeline());
    }
}
