package com.wf.wfballistics.damage;

import net.minecraft.world.damagesource.DamageSource;

/**
 * Resistance an entity works out for itself, for the cases a registered {@link
 * DamageResistanceHandler.ResistanceProfile} cannot express.
 *
 * <p>A profile is keyed by entity type and damage category, which assumes two things: that every individual
 * of a type resists the same, and that the category is enough to decide by. A glyphid breaks both. Its
 * threshold comes from how many chitin plates it still has, so two glyphids of the same type resist
 * differently and the same glyphid resists differently a second later; and it treats a laser and an
 * electrical arc differently despite both being {@code energy}.
 *
 * <p>So the entity is asked instead. What it returns is summed with whatever profiles it also has, which
 * means an entity can carry both a fixed innate resistance and a live one without either knowing about the
 * other.
 */
public interface DynamicResistance {

    /**
     * @return {@code [threshold, resistance]} against this specific source, right now
     */
    float[] currentDTDR(DamageSource source);
}
