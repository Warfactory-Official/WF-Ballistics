package com.wf.wflib.client.view;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmViewPoseTest {
    private static final float PX = 0.9375F / 16F;
    private static final float PISTOL_REACH = 0.92F;

    /** Classic right arm: {@code PlayerModel} box (-3, -2, -2) 4 x 12 x 4 at pivot (-5, 2, 0). */
    private static ModelPart rightArm() {
        ModelPart arm = new ModelPart(List.of(new ModelPart.Cube(40, 16, -3, -2, -2, 4, 12, 4, 0, 0, 0, false, 64, 64,
                EnumSet.allOf(Direction.class))), Map.of());
        arm.setInitialPose(PartPose.offset(-5, 2, 0));
        return arm;
    }

    @Test
    void shoulderIsTheVanillaPivotBelowAndBesideAStandingEye() {
        Vector3f shoulder = ArmViewPose.shoulder(rightArm(), new Vector3f());
        assertEquals(5 * PX, shoulder.x, 1e-6F);
        assertEquals(0.9375F * (1.501F - 2F / 16F) - 1.62F, shoulder.y, 1e-6F);
        assertEquals(0, shoulder.z, 1e-6F);
        assertEquals(0, shoulder.distance(ArmViewPose.shoulder(false, new Vector3f())), 1e-6F);
    }

    private static final PlayerArms.Lengths LENGTHS = new PlayerArms.Lengths(0.26F, 0.33F);

    /** Pistol: fist near the eye => shoulder slides back along the arm line, arm near straight, fist kept. */
    @Test
    void pistolMinimumReachMovesTheShoulderNotTheElbow() {
        Vector3f shoulder = ArmViewPose.shoulder(false, new Vector3f());
        Vector3f fist = new Vector3f(0.08F, -0.12F, -0.25F);
        Vector3f line = new Vector3f(fist).sub(shoulder).normalize();
        Vector3f elbow = new Vector3f();
        Matrix4f lower = new Matrix4f();
        ArmViewPose.solve(fist, null, shoulder, ArmViewPose.RIGHT_POLE, LENGTHS, PISTOL_REACH,
                new Matrix4f(), lower, elbow);
        float arm = LENGTHS.upper() + LENGTHS.forearm();
        assertEquals(PISTOL_REACH * arm, shoulder.distance(fist), 1e-5F, "reach floor");
        assertEquals(0, new Vector3f(fist).sub(shoulder).normalize().distance(line), 1e-5F, "along the arm line");
        assertEquals(0, lower.transformPosition(new Vector3f(0, LENGTHS.forearm(), 0)).distance(fist), 1e-5F);
    }

    @Test
    void reachableFistKeepsSegmentLengthsAndHandRoll() {
        Vector3f shoulder = ArmViewPose.shoulder(false, new Vector3f());
        Vector3f start = new Vector3f(shoulder);
        Vector3f fist = new Vector3f(0.12F, -0.15F, -0.35F);
        Quaternionf hand = new Quaternionf().rotateX(-1.3F).rotateZ(0.4F);
        Matrix4f upper = new Matrix4f(), lower = new Matrix4f();
        Vector3f elbow = new Vector3f();
        ArmViewPose.solve(fist, hand, shoulder, ArmViewPose.RIGHT_POLE, LENGTHS, upper, lower, elbow);

        assertEquals(0, shoulder.distance(start), 1e-6F, "reachable => shoulder planted");
        assertEquals(LENGTHS.upper(), elbow.distance(shoulder), 1e-5F);
        assertEquals(LENGTHS.forearm(), fist.distance(elbow), 1e-5F);
        assertEquals(0, lower.transformPosition(new Vector3f(0, LENGTHS.forearm(), 0)).distance(fist), 1e-5F,
                "fist end on the goal");
        assertEquals(0, upper.transformPosition(new Vector3f()).distance(shoulder), 1e-6F);
        Vector3f forearm = new Vector3f(fist).sub(elbow).normalize();
        Vector3f handAxis = hand.transform(new Vector3f(0, 1, 0));
        Vector3f handSide = hand.transform(new Vector3f(1, 0, 0));
        Vector3f expectedSide = new Quaternionf().rotationTo(handAxis, forearm).transform(handSide);
        assertEquals(0, lower.transformDirection(new Vector3f(1, 0, 0)).distance(expectedSide), 1e-5F,
                "forearm = hand frame swung onto the forearm");
        assertTrue(elbow.y < fist.y, "elbow below the hand");
    }

    @Test
    void outOfReachSlidesTheShoulderAndKeepsTheArmLength() {
        Vector3f shoulder = ArmViewPose.shoulder(false, new Vector3f());
        Vector3f fist = new Vector3f(-0.1F, -0.1F, -0.9F);
        Vector3f elbow = new Vector3f();
        ArmViewPose.solve(fist, null, shoulder, ArmViewPose.RIGHT_POLE, LENGTHS, new Matrix4f(), new Matrix4f(), elbow);
        assertEquals((LENGTHS.upper() + LENGTHS.forearm()) * ArmViewPose.MAX_REACH, fist.distance(shoulder), 1e-4F);
        assertEquals(LENGTHS.upper(), elbow.distance(shoulder), 1e-5F);
    }
}
