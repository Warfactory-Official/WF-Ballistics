package com.wf.wflib.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wf.wflib.tv.client.TvClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** TV link owns the mouse. Priority > default: outermost wrap, ahead of a vehicle mod's turret-aim wrap. */
@Mixin(value = MouseHandler.class, priority = 1500)
public abstract class MixinMouseHandler {

    @WrapOperation(method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void wflib$steerTv(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        if (!TvClient.steer(yaw, pitch)) {
            original.call(player, yaw, pitch);
        }
    }
}
