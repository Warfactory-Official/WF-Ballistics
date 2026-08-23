package com.wf.wfballistics.mixin;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips vanilla's per-tick equipment diff for glyphids that have never been given anything to wear.
 *
 * <p>{@code LivingEntity#detectEquipmentUpdates} walks all six {@code EquipmentSlot} values every tick,
 * pulling the previous stack out of a {@code NonNullList} and comparing it with the current one. For a bug
 * with nothing in any slot that is six array reads and six comparisons to establish that nothing changed,
 * and JFR measures it at 0.26 ms/tick across 2000 bodies — 1.5% of the swarm. Lithium's
 * {@code entity.equipment_tracking} is on in the dev runtime and this is what is left after it.
 *
 * <p>A mixin rather than an override because the method is private.
 *
 * <p><strong>Exactly equivalent</strong> for the case it fires in, which is why the gate is
 * "has never held anything" rather than "holds nothing now". With every slot empty the diff finds no
 * change, returns a null map, and {@code detectEquipmentUpdates} does nothing at all — so cancelling it is
 * the same as running it. An empty stack also cannot be mutated in place behind the flag's back, which is
 * the one way a dirty flag on {@code setItemSlot} could otherwise miss a change.
 *
 * <p>Scoped to glyphids and gated on the {@code instanceof} first, so every other living thing on the
 * server pays one type check per tick.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "detectEquipmentUpdates", at = @At("HEAD"), cancellable = true)
    private void wfballistics$skipBareEquipmentScan(CallbackInfo ci) {
        if ((Object) this instanceof EntityGlyphid glyphid && glyphid.equipmentEmpty()) {
            ci.cancel();
        }
    }
}
