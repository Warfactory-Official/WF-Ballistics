package com.wf.wfballistics.mixin;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips vanilla's per-tick equipment diff for glyphids that have never been given anything to wear. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "detectEquipmentUpdates", at = @At("HEAD"), cancellable = true)
    private void wfballistics$skipBareEquipmentScan(CallbackInfo ci) {
        if ((Object) this instanceof EntityGlyphid glyphid && glyphid.equipmentEmpty()) {
            ci.cancel();
        }
    }
}
