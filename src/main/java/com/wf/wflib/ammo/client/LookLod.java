package com.wf.wflib.ammo.client;

/**
 * Projectile look LOD: model while its diameter subtends >= {@link #MIN_WIDTH} px, else sprite to {@link #SPRITE_MAX}.
 * Sprite = the model's silhouette (length x diameter along the heading), floored at {@link #MIN_WIDTH} px wide, 2 px
 * long broadside (foreshortened below its width => square): at the switch both equal the model's. Thinner: centred on
 * a pixel boundary, covers no pixel centre.
 */
public final class LookLod {
    /** rad; 1 px at 1080 p, 70 deg. */
    public static final double PIXEL = 0.00113;
    public static final double MIN_WIDTH = 1.5;
    public static final double SPRITE_MAX = 512;

    private LookLod() {
    }

    public static boolean model(double diameterBlocks, double distance) {
        return diameterBlocks >= distance * PIXEL * MIN_WIDTH;
    }

    public static boolean sprite(double diameterBlocks, double distance) {
        return !model(diameterBlocks, distance) && distance <= SPRITE_MAX;
    }

    /** Sprite half-length, blocks. */
    public static double halfLength(double lengthBlocks, double distance) {
        return Math.max(lengthBlocks / 2, distance * PIXEL);
    }

    /** Sprite half-width, blocks. */
    public static double halfWidth(double diameterBlocks, double distance) {
        return Math.max(diameterBlocks / 2, distance * PIXEL * MIN_WIDTH / 2);
    }
}
