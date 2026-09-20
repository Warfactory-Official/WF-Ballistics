package com.wf.wflib.entity.glyphid.nav;

import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public final class GlyphidBridge {

    /** Columns a span may cover, bank to bank. Beyond this the swarm is better off digging or going round. */
    public static final int MAX_SPAN = 8;
    /** Height a caste may be and still anchor. */
    public static final float MAX_ANCHOR_HEIGHT = 1.0F;
    /** Ticks a claimed but unseated slot is held before the claim is dropped and offered to somebody else. */
    private static final int CLAIM_TIMEOUT = 100;
    /** Ticks a half-built bridge is kept before it is given up on and its anchors released. */
    private static final int FORM_TIMEOUT = 600;
    /** Ticks a finished bridge is kept after the last glyphid walked over it. */
    private static final int IDLE_TIMEOUT = 1200;

    /** One place an anchor stands. Ordered by distance from the bank; see the class note. */
    public static final class Slot {
        public final int x;
        public final int z;
        /** Position in the span, counting from the bank. Fixes what a recruit walks to; see {@link #approach}. */
        int index;
        /** The anchor's entity id, or -1 while nobody has claimed this slot. */
        int occupantId = -1;
        long claimedAtTick;
        /** True once the occupant is in place and holding still. */
        boolean seated;
        /** The seated anchor's hitbox, captured when it sat down. */
        @Nullable AABB box;

        Slot(int index, int x, int z) {
            this.index = index;
            this.x = x;
            this.z = z;
        }

        public boolean seated() {
            return seated;
        }

        public int occupantId() {
            return occupantId;
        }
    }

    /** Column the swarm arrives from, and the floor it is standing on there. */
    public int bankX;
    public int bankZ;
    /** The floor a crosser walks at: the bank's own floor, so the deck is flush with it. */
    public final int deckY;
    /** Column on the far side. Not a slot: something already stands there. */
    public int landingX;
    public int landingZ;

    private final Slot[] slots;
    /** Fixed once the span is laid out, and read on the movement hot path, so it is worked out here. */
    private final AABB bounds;
    /** How many slots, counting from the bank, are seated with no hole in between. */
    private int seated;
    private long lastUsedTick;
    private final long createdTick;

    GlyphidBridge(int bankX, int bankZ, int deckY, int landingX, int landingZ, Slot[] slots, long tick) {
        this.bankX = bankX;
        this.bankZ = bankZ;
        this.deckY = deckY;
        this.landingX = landingX;
        this.landingZ = landingZ;
        this.slots = slots;
        this.createdTick = tick;
        this.lastUsedTick = tick;

        int minX = Math.min(bankX, landingX);
        int maxX = Math.max(bankX, landingX);
        int minZ = Math.min(bankZ, landingZ);
        int maxZ = Math.max(bankZ, landingZ);
        for (Slot slot : slots) {
            minX = Math.min(minX, slot.x);
            maxX = Math.max(maxX, slot.x);
            minZ = Math.min(minZ, slot.z);
            maxZ = Math.max(maxZ, slot.z);
        }
        this.bounds = new AABB(minX, deckY - 2.0, minZ, maxX + 1.0, deckY + 2.0, maxZ + 1.0);
    }

    /** Turn the span round if this recruit is standing at the far end of it. */
    void orient(double x, double z) {
        if (seated > 0) {
            return;
        }
        for (Slot slot : slots) {
            if (slot.occupantId != -1) {
                return;
            }
        }
        double toBank = distanceSq(bankX, bankZ, x, z);
        double toLanding = distanceSq(landingX, landingZ, x, z);
        if (toLanding >= toBank) {
            return;
        }
        int swapX = bankX;
        int swapZ = bankZ;
        bankX = landingX;
        bankZ = landingZ;
        landingX = swapX;
        landingZ = swapZ;
        for (int i = 0, j = slots.length - 1; i < j; i++, j--) {
            Slot low = slots[i];
            slots[i] = slots[j];
            slots[j] = low;
        }
        for (int i = 0; i < slots.length; i++) {
            slots[i].index = i;
        }
    }

    private static double distanceSq(int blockX, int blockZ, double x, double z) {
        double dx = blockX + 0.5 - x;
        double dz = blockZ + 0.5 - z;
        return dx * dx + dz * dz;
    }

    /** How far this position is from the nearer end of the span, squared. */
    double distanceToEndSq(double x, double z) {
        return Math.min(distanceSq(bankX, bankZ, x, z), distanceSq(landingX, landingZ, x, z));
    }

    /**
     * The block a glyphid stands on to reach a slot: the bank for the first, and the deck the bridge has already
     * grown for every one after it.
     *
     * @return {@code {x, z}} of the block to approach from
     */
    public int[] approach(Slot slot) {
        return slot.index == 0
                ? new int[]{bankX, bankZ}
                : new int[]{slots[slot.index - 1].x, slots[slot.index - 1].z};
    }

    public Slot[] slots() {
        return slots;
    }

    public int seatedCount() {
        return seated;
    }

    /** Whether every slot is seated, which is the only state the deck is offered to navigation in. */
    public boolean complete() {
        return seated == slots.length;
    }

    /**
     * The next slot somebody may walk out to, or null when the bridge is finished or already has a claim
     * outstanding.
     */
    public @Nullable Slot offer(long tick) {
        if (seated >= slots.length) {
            return null;
        }
        Slot next = slots[seated];
        if (next.occupantId != -1 && tick - next.claimedAtTick < CLAIM_TIMEOUT) {
            return null;
        }
        return next;
    }

    public void claim(Slot slot, int occupantId, long tick) {
        slot.occupantId = occupantId;
        slot.claimedAtTick = tick;
        slot.seated = false;
        slot.box = null;
    }

    /**
     * Note that an occupant is in place.
     *
     * @return true if this completed the bridge
     */
    public boolean seat(Slot slot, AABB box, long tick) {
        slot.seated = true;
        slot.box = box;
        while (seated < slots.length && slots[seated].seated) {
            seated++;
        }
        lastUsedTick = tick;
        return complete();
    }

    /**
     * Give up a slot, and with it every slot further out: the deck past a hole cannot be reached, so holding those
     * anchors would strand them over the gap.
     *
     * @return the ids of every occupant that has to be let go, this one included, or an empty array if this
     *      glyphid did not hold a slot here
     */
    public int[] release(int occupantId) {
        int from = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].occupantId == occupantId) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return EMPTY;
        }
        return releaseFrom(from);
    }

    /** Let go of every occupant, for a bridge that is being dissolved. */
    public int[] releaseAll() {
        return releaseFrom(0);
    }

    private int[] releaseFrom(int from) {
        int count = 0;
        for (int i = from; i < slots.length; i++) {
            if (slots[i].occupantId != -1) {
                count++;
            }
        }
        int[] freed = new int[count];
        int at = 0;
        for (int i = from; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot.occupantId != -1) {
                freed[at++] = slot.occupantId;
            }
            slot.occupantId = -1;
            slot.seated = false;
            slot.box = null;
        }
        seated = Math.min(seated, from);
        return freed;
    }

    /** Note that something crossed, which is what keeps a finished bridge from being dissolved. */
    void used(long tick) {
        lastUsedTick = tick;
    }

    /** Whether this bridge has stopped being worth the glyphids standing in it. */
    boolean expired(long tick) {
        return complete()
                ? tick - lastUsedTick > IDLE_TIMEOUT
                : tick - createdTick > FORM_TIMEOUT;
    }

    /** Whether this bridge's span passes through a column, so a field knows to stamp it. */
    boolean spans(int blockX, int blockZ) {
        for (Slot slot : slots) {
            if (slot.x == blockX && slot.z == blockZ) {
                return true;
            }
        }
        return false;
    }

    /** The box every part of this bridge is inside, for the broad test before anything finer is asked. */
    public AABB bounds() {
        return bounds;
    }

    private static final int[] EMPTY = new int[0];
}
