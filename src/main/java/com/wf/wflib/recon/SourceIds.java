package com.wf.wflib.recon;

import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** Stable ids for targets, so one pass can tell two snapshots of the same thing apart from two things. */
public final class SourceIds {

    /**
     * Glyphid sim records are numbered from one per level, so they need a space of their own rather than a fold.
     */
    private static final long GLYPHID_SPACE = 0x6C79_0000_0000_0000L;
    /**
     * Decoys are numbered per level for the same reason glyphid sim records are, and get their own space so a cloud
     * of them can never collide with one.
     */
    private static final long DECOY_SPACE = 0x6465_0000_0000_0000L;

    private SourceIds() {
    }

    public static long of(UUID uuid) {
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }

    public static long of(Entity entity) {
        return of(entity.getUUID());
    }

    public static long ofGlyphid(int simId) {
        return GLYPHID_SPACE | (simId & 0xFFFF_FFFFL);
    }

    public static long ofDecoy(int decoyId) {
        return DECOY_SPACE | (decoyId & 0xFFFF_FFFFL);
    }
}
