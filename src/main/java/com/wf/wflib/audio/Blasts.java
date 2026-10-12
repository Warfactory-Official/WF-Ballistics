package com.wf.wflib.audio;

import com.wf.wflib.WFSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/** Built-in blast layers, {@link AudioCategory#EXPLOSIONS}. Near recording close, far recording faded in past it. */
public final class Blasts {
    private static final ResourceLocation VANILLA = SoundEvents.GENERIC_EXPLODE.value().getLocation();

    private Blasts() {
    }

    /** Grenade-to-shell blast. */
    public static List<SoundSpec> small() {
        return List.of(
                new SoundSpec(WFSounds.EXPLOSION_SMALL_NEAR.getId(), 1, 0.95F, 0.1F, 150, 0),
                new SoundSpec(WFSounds.EXPLOSION_SMALL_FAR.getId(), 1, 0.95F, 0.1F, 400, 0, 60));
    }

    /** Bomb / missile blast; {@code range}: near layer reach, far layer carries 3x. */
    public static List<SoundSpec> large(float range) {
        return List.of(
                new SoundSpec(WFSounds.EXPLOSION_LARGE_NEAR.getId(), 1, 0.9F, 0.2F, range, 0),
                new SoundSpec(WFSounds.EXPLOSION_LARGE_FAR.getId(), 1, 0.9F, 0.2F, 3 * range, 0, 0.4F * range));
    }

    /** Vanilla report for minor pops (interceptor graze, APS charge, drone debris); {@code range} blocks. */
    public static List<SoundSpec> pop(float range, float pitch) {
        return List.of(new SoundSpec(VANILLA, 1, pitch, 0.1F, range, 0));
    }

    public static List<SoundSpec> sonicBoom() {
        return List.of(new SoundSpec(WFSounds.SONIC_BOOM.getId(), 1, 0.8F, 0.2F, 600, 0));
    }

    /** Mining charge: sharp report close, recorded rumble past 24 blocks. */
    public static List<SoundSpec> miningCharge() {
        return List.of(
                new SoundSpec(WFSounds.MINING_CHARGE_BLAST.getId(), 1, 1, 0, 48, 0),
                new SoundSpec(WFSounds.MINING_CHARGE_BLAST_DISTANT.getId(), 1, 1, 0, 200, 0, 24));
    }
}
