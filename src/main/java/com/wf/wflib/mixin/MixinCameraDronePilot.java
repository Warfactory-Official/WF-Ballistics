package com.wf.wflib.mixin;

import com.wf.wflib.drone.DroneEntity;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Piloted {@link DroneEntity} (camera entity, first person) => eye at its lens. */
@Mixin(Camera.class)
public abstract class MixinCameraDronePilot {

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At("TAIL"))
    private void wflib$droneLens(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse,
                                 float partialTick, CallbackInfo ci) {
        if (!detached && entity instanceof DroneEntity drone) {
            this.setPosition(drone.lensPosition(partialTick));
        }
    }
}
