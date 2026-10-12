package com.wf.wflib.ammo;

import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.warhead.WarheadRegistry;

/** Variant -> preset under the variant id; TANK kind alone loads chunks. Effect unbound / bad params => IAE. */
public final class AmmoPresets {
    private AmmoPresets() {
    }

    public static KineticPreset preset(Calibre c, AmmoVariant v) {
        KineticPreset.Builder b = KineticPreset.builder(v.id(), null,
                        v.explosive() != null ? v.explosive().warhead() : WarheadRegistry.rl("inert"))
                .speed(v.speed() / 20.0)
                .drag(v.drag())
                .quadraticDrag(v.quadraticDrag())
                .gravity(v.gravity() / 400.0)
                .mass(v.projectileMassKg())
                .energyDamage(v.damagePerKJ())
                .caliber(c.bulletMm())
                .blockPen(v.blockPen())
                .armorPenetration(v.armorPenMm())
                .stackSize(c.stackSize());
        if (c.kind() != CalibreKind.TANK) {
            b.noChunkLoading();
        }
        if (v.tracer() != AmmoVariant.NONE) {
            b.tracer(v.tracer());
        }
        if (v.pierce() != null) {
            b.pierce(v.pierce().dt(), v.pierce().dr());
        }
        if (v.explosive() != null && v.explosive().size() > 0) {
            b.blast(v.explosive().size(), true);
        }
        if (v.motor() != null) {
            b.motor(v.motor().accel() / 400.0, Math.round(v.motor().burn() * 20));
        }
        if (v.explosive() == null && v.armorPenMm() > 0 && c.bulletMm() >= 20) {
            b.threat(ThreatKind.KINETIC);
        }
        for (AmmoVariant.Effect e : v.effects()) {
            b.effect(e.id(), e.on(), e.chance(), e.params());
        }
        return b.build();
    }
}
