package com.wf.wflib.recon.source;

import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.recon.Signature;

/** What the things this mod ships look like to a sensor. */
public final class ReconSignatures {

    /**
     * Reference cross-section of a one-block-wide target, so a grunt lands near a quarter and a behemoth near one.
     */
    private static final float BODY_RCS = 0.25f;
    /** How much each armour plate adds. */
    private static final float ARMOR_GAIN = 0.15f;
    /** Seismic energy per point of dig strength. */
    private static final float DIG_SEISMIC = 0.5f;
    /** Thermal per square block of body. */
    private static final float BODY_THERMAL = 0.15f;
    /** A running rocket motor. */
    private static final float MOTOR_THERMAL = 2.25f;
    /**
     * A torpedo's screws. Twice a vehicle engine at full, because it is the loudest thing in the water and
     * the only reason a passive set ever hears one coming.
     */
    private static final float SCREW_ACOUSTIC = 3.0f;

    private ReconSignatures() {
    }

    /**
     * @param armorBits how many armour plates are intact, 0-5.
     */
    public static Signature glyphid(GlyphidCaste caste, int armorBits) {
        double scale = caste.scale();
        float rcs = (float) (BODY_RCS * scale * scale * (1.0f + ARMOR_GAIN * armorBits));
        float seismic = (float) (caste.stats().digStrength() * DIG_SEISMIC);
        float thermal = (float) (BODY_THERMAL * scale * scale);
        return new Signature(rcs, seismic, thermal, 0.0f, 0.0f);
    }

    public static Signature glyphid(EntityGlyphid glyphid) {
        return glyphid(GlyphidCaste.byType(glyphid.getType()), Integer.bitCount(glyphid.armor() & 0xFF));
    }

    /** A small airframe of composite and battery. */
    public static Signature drone() {
        return new Signature(0.15f, 0.0f, 0.3f, 0.0f, 1.0f);
    }

    /** A missile: whatever cross-section it was built with, and a motor that gives it away. */
    public static Signature missile(float rcs) {
        return missile(rcs, false);
    }

    /**
     * @param torpedo true for something that travels through water. Its thermal and radar terms are carried
     *      unchanged and simply never reach a sensor while it is under, which the {@code buried} flag
     *      on its snapshot already sees to.
     */
    public static Signature missile(float rcs, boolean torpedo) {
        return new Signature(rcs, 0.0f, MOTOR_THERMAL, torpedo ? SCREW_ACOUSTIC : 0.0f, 0.0f);
    }

    /** Nothing distinguishing: about a grunt's return, which is roughly right for a person in armour. */
    public static Signature player() {
        return new Signature(0.2f, 0.0f, 0.05f, 0.0f, 0.0f);
    }
}
