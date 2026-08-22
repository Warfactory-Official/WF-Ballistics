package com.wf.wfballistics.client.model;

import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * The glyphid's skeleton, precomputed.
 *
 * <p>Every joint in the rig is driven by exactly one scalar: the six legs and the two arms by the walk
 * cycle's phase, the three jaw plates and the head tilt they hang off by how far through a bite the animal
 * is. Nothing depends on which glyphid it is. So the poses are not per-entity state at all — they are a
 * function of one number, and a function of one number over a bounded range is a table.
 *
 * <p>That is what makes three hundred of them affordable. Posing a bug is not thirty trig calls and a chain
 * of matrix builds any more; it is a table lookup and one multiply per part against the body transform. The
 * quantisation is the only cost, and at {@link #WALK_STEPS} steps a leg moves under a degree between
 * neighbouring entries — well under what the shape of the leg itself resolves at any distance you can see
 * one from.
 *
 * <p>Joint angles below are in degrees, matching the units the rig was authored in.
 */
public final class GlyphidPoses {

    /**
     * Parts that do not move relative to the body: the shell and its three fixed plates.
     */
    public static final int BODY = 0;
    public static final int ARMOR_FRONT = 1;
    public static final int ARMOR_LEFT = 2;
    public static final int ARMOR_RIGHT = 3;

    /**
     * Parts driven by the bite, first index into {@link #bite}.
     */
    public static final int JAW_FIRST = 4;
    public static final int JAW_COUNT = 3;

    /**
     * Parts driven by the walk cycle, first index into {@link #walk}: both arms, then six legs in
     * upper/lower pairs.
     */
    public static final int WALK_FIRST = 7;
    public static final int WALK_COUNT = 20;

    public static final int PART_COUNT = WALK_FIRST + WALK_COUNT;

    /**
     * Which part each of the five armour bits switches off, in {@code EntityGlyphid.armor()} bit order.
     */
    public static final int[] ARMOR_PART = {ARMOR_FRONT, ARMOR_LEFT, ARMOR_RIGHT, 10, 14};

    /**
     * Quantisation of the walk cycle. A whole cycle is {@code 2*pi} of limb swing.
     */
    private static final int WALK_STEPS = 128;
    /**
     * Quantisation of the bite. The attack animation runs 0 to 1 and is over in six ticks, so it is coarse
     * time to begin with; this splits it finer than the ticks it arrives on.
     */
    private static final int BITE_STEPS = 32;

    /**
     * How far a leg lifts and swings, and how far the knee is bent, degrees.
     */
    private static final double STEP = 15.0;
    private static final double BEND = 60.0;

    private static final Matrix4f[][] WALK = new Matrix4f[WALK_STEPS][];
    private static final Matrix4f[][] BITE = new Matrix4f[BITE_STEPS + 1][];

    static {
        for (int i = 0; i < WALK_STEPS; i++) {
            WALK[i] = buildWalk(Math.PI * 2.0 * i / WALK_STEPS);
        }
        for (int i = 0; i <= BITE_STEPS; i++) {
            BITE[i] = buildBite((double) i / BITE_STEPS);
        }
    }

    private GlyphidPoses() {
    }

    /**
     * @param limbSwing the accumulated walk position, radians
     * @return local transforms for parts {@link #WALK_FIRST} upward, in part order
     */
    public static Matrix4f[] walk(float limbSwing) {
        float cycle = limbSwing / (float) (Math.PI * 2.0);
        int step = Mth.floor((cycle - Mth.floor(cycle)) * WALK_STEPS);
        return WALK[Mth.clamp(step, 0, WALK_STEPS - 1)];
    }

    /**
     * @param swing how far through a bite, 0 to 1
     * @return local transforms for the three jaw plates, in part order from {@link #JAW_FIRST}
     */
    public static Matrix4f[] bite(float swing) {
        return BITE[Mth.clamp(Mth.floor(swing * BITE_STEPS + 0.5f), 0, BITE_STEPS)];
    }

    private static Matrix4f[] buildWalk(double cycle) {
        // Four samples of one cycle at different offsets. Between them they give each limb a phase of its
        // own, which is the whole of what makes six legs read as walking rather than twitching in unison.
        double cy0 = Math.sin(cycle);
        double cy1 = Math.sin(cycle - Math.PI * 0.5);
        double cy2 = Math.sin(cycle - Math.PI);
        double cy3 = Math.sin(cycle - Math.PI * 0.75);

        Matrix4f[] out = new Matrix4f[WALK_COUNT];

        // Left arm: three segments on one chain, each posed on top of the one before, with the forearm's
        // armour plate riding the forearm.
        Matrix4f arm = new Matrix4f()
                .translate(0.25f, 0.625f, 0.0625f)
                .rotateY(rad(10.0))
                .rotateX(rad(35.0 + cy1 * 20.0))
                .translate(-0.25f, -0.625f, -0.0625f);
        out[0] = new Matrix4f(arm);
        arm.translate(0.25f, 0.625f, 0.4375f)
                .rotateX(rad(-75.0 - cy1 * 20.0 + cy0 * 20.0))
                .translate(-0.25f, -0.625f, -0.4375f);
        out[1] = new Matrix4f(arm);
        arm.translate(0.25f, 0.625f, 0.9375f)
                .rotateX(rad(90.0 - cy0 * 45.0))
                .translate(-0.25f, -0.625f, -0.9375f);
        out[2] = new Matrix4f(arm);
        out[3] = out[2];

        // Right arm, half a cycle out of step with the left.
        arm = new Matrix4f()
                .translate(-0.25f, 0.625f, 0.0625f)
                .rotateY(rad(-10.0))
                .rotateX(rad(35.0 + cy2 * 20.0))
                .translate(0.25f, -0.625f, -0.0625f);
        out[4] = new Matrix4f(arm);
        arm.translate(-0.25f, 0.625f, 0.4375f)
                .rotateX(rad(-75.0 - cy2 * 20.0 + cy3 * 20.0))
                .translate(0.25f, -0.625f, -0.4375f);
        out[5] = new Matrix4f(arm);
        arm.translate(-0.25f, 0.625f, 0.9375f)
                .rotateX(rad(90.0 - cy3 * 45.0))
                .translate(0.25f, -0.625f, -0.9375f);
        out[6] = new Matrix4f(arm);
        out[7] = out[6];

        // Six legs from one pair of meshes: fanned around the body by index, and with the middle pair's
        // phase inverted so the animal moves on an alternating tripod the way an insect actually does.
        for (int i = 0; i < 3; i++) {
            double c0 = cy0 * (i == 1 ? -1.0 : 1.0);
            double c1 = cy1 * (i == 1 ? -1.0 : 1.0);

            Matrix4f leg = new Matrix4f()
                    .translate(0.0f, 0.25f, 0.0f)
                    .rotateY(rad(i * 30.0 - 15.0 + c0 * 7.5))
                    .rotateZ(rad(STEP + c1 * STEP))
                    .translate(0.0f, -0.25f, 0.0f);
            out[8 + i * 2] = new Matrix4f(leg);
            leg.translate(0.5625f, 0.25f, 0.0f)
                    .rotateZ(rad(-BEND - c1 * STEP))
                    .translate(-0.5625f, -0.25f, 0.0f);
            out[9 + i * 2] = leg;

            leg = new Matrix4f()
                    .translate(0.0f, 0.25f, 0.0f)
                    .rotateY(rad(i * 30.0 - 45.0 + c0 * 7.5))
                    .rotateZ(rad(-STEP + c1 * STEP))
                    .translate(0.0f, -0.25f, 0.0f);
            out[14 + i * 2] = new Matrix4f(leg);
            leg.translate(-0.5625f, 0.25f, 0.0f)
                    .rotateZ(rad(BEND - c1 * STEP))
                    .translate(0.5625f, -0.25f, 0.0f);
            out[15 + i * 2] = leg;
        }

        return out;
    }

    private static Matrix4f[] buildBite(double swing) {
        // The mandibles are shut for the first half of the swing and snap through the second, so the bite
        // lands with the blow rather than opening as the animal winds up.
        double bite = Mth.clamp(Math.sin(swing * Math.PI * 2.0 - Math.PI * 0.5), 0.0, 1.0) * 20.0;
        double headTilt = Math.sin(swing * Math.PI) * 30.0;

        Matrix4f head = new Matrix4f()
                .translate(0.0f, 0.5f, 0.25f)
                .rotateZ(rad(headTilt))
                .translate(0.0f, -0.5f, -0.25f);

        return new Matrix4f[]{
                new Matrix4f(head)
                        .translate(0.0f, 0.5f, 0.25f)
                        .rotateX(rad(-bite))
                        .translate(0.0f, -0.5f, -0.25f),
                new Matrix4f(head)
                        .translate(0.0f, 0.5f, 0.25f)
                        .rotateY(rad(bite))
                        .rotateX(rad(bite))
                        .translate(0.0f, -0.5f, -0.25f),
                new Matrix4f(head)
                        .translate(0.0f, 0.5f, 0.25f)
                        .rotateY(rad(-bite))
                        .rotateX(rad(bite))
                        .translate(0.0f, -0.5f, -0.25f)};
    }

    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }
}
