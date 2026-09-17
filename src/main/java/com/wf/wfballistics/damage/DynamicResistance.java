package com.wf.wfballistics.damage;

import net.minecraft.world.damagesource.DamageSource;

/**
 * Resistance an entity works out for itself, for the cases a registered {@link
 * DamageResistanceHandler.ResistanceProfile} cannot express.
 */
public interface DynamicResistance {

    /**
     * @return {@code [threshold, resistance]} against this specific source, right now
     */
    float[] currentDTDR(DamageSource source);
}
