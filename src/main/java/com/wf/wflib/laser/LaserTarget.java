package com.wf.wflib.laser;

import net.minecraft.world.phys.Vec3;

/** What a beam has to heat: answered by bodies that know; others are estimated ({@link LaserDamage#massOf}). */
public interface LaserTarget {

    double laserMassKg();

    /** RHA mm on the face the beam meets. */
    double laserArmorMm(Vec3 beamDirection);

    /** Multiplies the mass/armour term; pack override. 1 = formula as is. */
    default double laserResistance() {
        return 1.0;
    }
}
