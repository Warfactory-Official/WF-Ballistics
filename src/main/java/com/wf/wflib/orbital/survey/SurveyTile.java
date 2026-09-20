package com.wf.wflib.orbital.survey;

import net.minecraft.nbt.CompoundTag;

/** One square kilometre of imaged ground. */
public final class SurveyTile {

    /** Pixels per side. 128, like a filled map, because that is a sensible texture and a sensible packet. */
    public static final int PIXELS = 128;
    /** Blocks per pixel. Eight, so one tile is exactly 1024 blocks: a square kilometre in game units. */
    public static final int BLOCKS_PER_PIXEL = 8;
    public static final int BLOCKS = PIXELS * BLOCKS_PER_PIXEL;

    private final int tileX;
    private final int tileZ;
    private final byte[] material = new byte[PIXELS * PIXELS];
    private final byte[] height = new byte[PIXELS * PIXELS];
    private final byte[] changed = new byte[PIXELS * PIXELS];
    private long stamped;
    private int imaged;

    public SurveyTile(int tileX, int tileZ) {
        this.tileX = tileX;
        this.tileZ = tileZ;
    }

    public int tileX() {
        return tileX;
    }

    public int tileZ() {
        return tileZ;
    }

    /** @return the game tick this tile was last touched by a pass. What makes staleness drawable. */
    public long stamped() {
        return stamped;
    }

    /** @return pixels imaged at least once, of {@link #PIXELS}². A partial strip is a real product. */
    public int imaged() {
        return imaged;
    }

    public boolean complete() {
        return imaged >= PIXELS * PIXELS;
    }

    public byte[] material() {
        return material;
    }

    public byte[] height() {
        return height;
    }

    public byte[] changed() {
        return changed;
    }

    /**
     * Record one column.
     *
     * @param packedColour vanilla's {@code MapColor.id * 4 + brightness}.
     * @param top world height at the column, biased into a byte.
     */
    public void set(int px, int pz, byte packedColour, byte top, long gameTime) {
        int i = pz * PIXELS + px;
        if (material[i] == 0) {
            imaged++;
        } else if (material[i] != packedColour || Math.abs((height[i] & 0xFF) - (top & 0xFF)) > 1) {
            changed[i] = 1;
        }
        material[i] = packedColour;
        height[i] = top;
        stamped = gameTime;
    }

    public void clearChanges() {
        java.util.Arrays.fill(changed, (byte) 0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", tileX);
        tag.putInt("Z", tileZ);
        tag.putLong("Stamped", stamped);
        tag.putInt("Imaged", imaged);
        tag.putByteArray("Material", material);
        tag.putByteArray("Height", height);
        tag.putByteArray("Changed", changed);
        return tag;
    }

    public static SurveyTile load(CompoundTag tag) {
        SurveyTile tile = new SurveyTile(tag.getInt("X"), tag.getInt("Z"));
        tile.stamped = tag.getLong("Stamped");
        tile.imaged = tag.getInt("Imaged");
        copy(tag.getByteArray("Material"), tile.material);
        copy(tag.getByteArray("Height"), tile.height);
        copy(tag.getByteArray("Changed"), tile.changed);
        return tile;
    }

    private static void copy(byte[] from, byte[] into) {
        System.arraycopy(from, 0, into, 0, Math.min(from.length, into.length));
    }

    public static long key(int tileX, int tileZ) {
        return (((long) tileX) << 32) ^ (tileZ & 0xFFFF_FFFFL);
    }

    public static int tileOf(double world) {
        return Math.floorDiv((int) Math.floor(world), BLOCKS);
    }
}
