package com.wf.wflib.armor;

import java.util.List;

/**
 * What one resolved hit did. Deliberately not a single float: a number alone cannot distinguish
 * "the plate stopped it" from "nothing was there", and those produce different injuries.
 *
 * @param type           what the hit was resolved as
 * @param amount         damage that arrived
 * @param through        damage that reached the body, and so the wound energy WFMedical works from
 * @param absorbed       damage the armour ate; drives wear and backface trauma
 * @param outcome        the qualitative half
 * @param wear           per-layer condition cost, indexed into the layer list that was passed in.
 *                       Attributed rather than totalled, because the layer that ate the hit is the
 *                       layer that wears out.
 * @param worstCondition condition fraction of the worst contributing layer after this hit, for the
 *                       player-facing readout. A model the player cannot see reads as random damage.
 */
public record ArmorResult(
        ProtectionType type,
        double amount,
        double through,
        double absorbed,
        ArmorOutcome outcome,
        List<LayerWear> wear,
        double worstCondition
) {
    public record LayerWear(int index, int points) {
    }

    /** Nothing was worn, or nothing the hit cared about. */
    public static ArmorResult untouched(ProtectionType type, double amount) {
        return new ArmorResult(type, amount, amount, 0.0D, ArmorOutcome.THROUGH, List.of(), 1.0D);
    }

    public boolean stopped() {
        return outcome == ArmorOutcome.STOPPED;
    }
}
