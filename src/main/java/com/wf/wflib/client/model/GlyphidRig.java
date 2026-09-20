package com.wf.wflib.client.model;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.gltf.NodeHide;
import com.wf.gemrender.gltf.NodeOscillate;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;
import com.wf.gemrender.rig.RigBuilder;
import com.wf.gemrender.rig.RigGeometry;
import com.wf.gemrender.rig.WavefrontObj;
import com.wf.wflib.WFLib;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/** The glyphid's skeleton, declared rather than tabulated. */
public final class GlyphidRig {

    public static final ResourceLocation MESH =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "raw_models/glyphid.obj");

    /** The walk clip, scrubbed by accumulated limb swing in radians. */
    public static final String WALK = "walk";
    /** The bite clip, scrubbed by the attack animation's 0-to-1 progress. */
    public static final String BITE = "bite";

    /** How far a leg lifts and swings, and how far the knee is bent, degrees. */
    private static final double STEP = 15.0;
    private static final double BEND = 60.0;
    /** How far an arm segment swings about its own joint, degrees. */
    private static final double ARM_SWING = 20.0;
    /** How far the forearm folds, degrees. */
    private static final double ARM_FOLD = 45.0;
    /** How far a mandible swings at full bite, and how far the head dips through one, degrees. */
    private static final double JAW_SNAP = 20.0;
    private static final double HEAD_TILT = 30.0;

    /**
     * One walk cycle is one unit of clip-local time, so the drives that scrub it are in whole strides and the pose
     * cache's buckets fall across a cycle rather than across a second.
     */
    private static final float CYCLE = 1.0f;

    private static final float TAU = (float) (Math.PI * 2.0);

    /** The five plates an {@link EntityGlyphid#armor()} bit switches off, in bit order. */
    private static final int ARMOR_BITS = 5;

    private GlyphidRig() {
    }

    /** Read the mesh, hang it on the skeleton, and hand back a model wearing {@code material}. */
    public static GemRenderGltfModel build(String name, Material material) throws IOException {
        return build(name, material, null, List.of());
    }

    /**
     * The rig wearing every skin at once: one mesh, one instancer, one draw for the whole swarm.
     *
     * @param skins one texture per caste, in {@code GlyphidCaste} order, or empty for the plain rig
     */
    public static GemRenderGltfModel build(String name, Material material,
                                           @Nullable ResourceLocation atlas,
                                           List<ResourceLocation> skins) throws IOException {
        Map<String, RigGeometry> groups = WavefrontObj.load(MESH);
        RigBuilder rig = new RigBuilder(name);

        if (atlas != null && skins.size() > 1) {
            rig.skins(atlas, skins);
        }

        int body = rig.bone("body", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);

        // The head is a joint with no geometry: the bite tilts it, and all three plates ride it.
        int head = rig.bone("head", body, 0.0f, 0.5f, 0.25f);
        int jawTop = rig.bone("jaw_top", head, 0.0f, 0.0f, 0.0f);
        int jawLeft = rig.bone("jaw_left", head, 0.0f, 0.0f, 0.0f);
        int jawRight = rig.bone("jaw_right", head, 0.0f, 0.0f, 0.0f);

        int armorFront = rig.bone("armor_front", body, 0.0f, 0.0f, 0.0f);
        int armorLeft = rig.bone("armor_left", body, 0.0f, 0.0f, 0.0f);
        int armorRight = rig.bone("armor_right", body, 0.0f, 0.0f, 0.0f);

        int leftUpper = rig.bone("arm_l_upper", body, 0.25f, 0.625f, 0.0625f);
        int leftMid = rig.bone("arm_l_mid", leftUpper, 0.0f, 0.0f, 0.375f);
        int leftLower = rig.bone("arm_l_lower", leftMid, 0.0f, 0.0f, 0.5f);
        int leftArmor = rig.bone("arm_l_armor", leftLower, 0.0f, 0.0f, 0.0f);

        int rightUpper = rig.bone("arm_r_upper", body, -0.25f, 0.625f, 0.0625f);
        int rightMid = rig.bone("arm_r_mid", rightUpper, 0.0f, 0.0f, 0.375f);
        int rightLower = rig.bone("arm_r_lower", rightMid, 0.0f, 0.0f, 0.5f);
        int rightArmor = rig.bone("arm_r_armor", rightLower, 0.0f, 0.0f, 0.0f);

        int[] legUpper = new int[6];
        int[] legLower = new int[6];
        for (int i = 0; i < 3; i++) {
            legUpper[i] = rig.bone("leg_l" + i + "_upper", body, 0.0f, 0.25f, 0.0f);
            legLower[i] = rig.bone("leg_l" + i + "_lower", legUpper[i], 0.5625f, 0.0f, 0.0f);

            legUpper[3 + i] = rig.bone("leg_r" + i + "_upper", body, 0.0f, 0.25f, 0.0f);
            legLower[3 + i] = rig.bone("leg_r" + i + "_lower", legUpper[3 + i], -0.5625f, 0.0f, 0.0f);
        }

        NodeTable table = rig.table();

        rig.attach(body, groups, "Body")
                .attach(armorFront, groups, "ArmorFront")
                .attach(armorLeft, groups, "ArmorLeft")
                .attach(armorRight, groups, "ArmorRight")
                .attach(jawTop, groups, "JawTop")
                .attach(jawLeft, groups, "JawLeft")
                .attach(jawRight, groups, "JawRight")
                .attach(leftUpper, groups, "ArmLeftUpper")
                .attach(leftMid, groups, "ArmLeftMid")
                .attach(leftLower, groups, "ArmLeftLower")
                .attach(leftArmor, groups, "ArmLeftArmor")
                .attach(rightUpper, groups, "ArmRightUpper")
                .attach(rightMid, groups, "ArmRightMid")
                .attach(rightLower, groups, "ArmRightLower")
                .attach(rightArmor, groups, "ArmRightArmor");
        for (int i = 0; i < 3; i++) {
            rig.attach(legUpper[i], groups, "LegLeftUpper")
                    .attach(legLower[i], groups, "LegLeftLower")
                    .attach(legUpper[3 + i], groups, "LegRightUpper")
                    .attach(legLower[3 + i], groups, "LegRightLower");
        }

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(WALK, walk(table, leftUpper, leftMid, leftLower, rightUpper, rightMid, rightLower,
                legUpper, legLower));
        clips.put(BITE, bite(table, head, jawTop, jawLeft, jawRight));
        armour(table, clips, armorFront, armorLeft, armorRight, leftArmor, rightArmor);

        return rig.build(material, clips);
    }

    /**
     * The walk cycle: eight limbs on one clock at eight different phases, which is the whole of what makes six legs
     * read as walking rather than twitching in unison.
     */
    private static GltfAnimation walk(NodeTable table, int leftUpper, int leftMid, int leftLower,
                                      int rightUpper, int rightMid, int rightLower,
                                      int[] legUpper, int[] legLower) {
        List<PoseDriver> drivers = new ArrayList<>();

        float lead = -0.25f;
        float trail = -0.5f;
        float follow = -0.375f;

        drivers.add(NodeOscillate.fixed(table, leftUpper, 0.0f, 1.0f, 0.0f, rad(10.0)));
        drivers.add(swing(table, leftUpper, 1.0f, 0.0f, 0.0f, 35.0, ARM_SWING, lead));
        float[] leftElbow = combine(ARM_SWING, 0.0f, -ARM_SWING, lead);
        drivers.add(NodeOscillate.about(table, leftMid, 1.0f, 0.0f, 0.0f, rad(-75.0), rad(leftElbow[0]),
                CYCLE, leftElbow[1]));
        drivers.add(swing(table, leftLower, 1.0f, 0.0f, 0.0f, 90.0, -ARM_FOLD, 0.0f));

        // Right arm, half a cycle out of step with the left.
        drivers.add(NodeOscillate.fixed(table, rightUpper, 0.0f, 1.0f, 0.0f, rad(-10.0)));
        drivers.add(swing(table, rightUpper, 1.0f, 0.0f, 0.0f, 35.0, ARM_SWING, trail));
        float[] rightElbow = combine(-ARM_SWING, trail, ARM_SWING, follow);
        drivers.add(NodeOscillate.about(table, rightMid, 1.0f, 0.0f, 0.0f, rad(-75.0), rad(rightElbow[0]),
                CYCLE, rightElbow[1]));
        drivers.add(swing(table, rightLower, 1.0f, 0.0f, 0.0f, 90.0, -ARM_FOLD, follow));

        for (int i = 0; i < 3; i++) {
            double flip = i == 1 ? -1.0 : 1.0;

            drivers.add(swing(table, legUpper[i], 0.0f, 1.0f, 0.0f, i * 30.0 - 15.0, 7.5 * flip, 0.0f));
            drivers.add(swing(table, legUpper[i], 0.0f, 0.0f, 1.0f, STEP, STEP * flip, lead));
            drivers.add(swing(table, legLower[i], 0.0f, 0.0f, 1.0f, -BEND, -STEP * flip, lead));

            drivers.add(swing(table, legUpper[3 + i], 0.0f, 1.0f, 0.0f, i * 30.0 - 45.0, 7.5 * flip, 0.0f));
            drivers.add(swing(table, legUpper[3 + i], 0.0f, 0.0f, 1.0f, -STEP, STEP * flip, lead));
            drivers.add(swing(table, legLower[3 + i], 0.0f, 0.0f, 1.0f, BEND, -STEP * flip, lead));
        }

        return GltfAnimation.procedural(WALK, CYCLE, drivers.toArray(new PoseDriver[0]));
    }

    /** The bite: the head dips through the swing while the three plates snap shut on the second half of it. */
    private static GltfAnimation bite(NodeTable table, int head, int jawTop, int jawLeft, int jawRight) {
        return GltfAnimation.procedural(BITE, 1.0f,
                // sin(pi * t) over the one unit the bite lasts: up and back down, peaking mid-swing.
                NodeOscillate.about(table, head, 0.0f, 0.0f, 1.0f, 0.0f, rad(HEAD_TILT), 2.0f, 0.0f),
                GlyphidBite.about(table, jawTop, 1.0f, 0.0f, 0.0f, rad(-JAW_SNAP)),
                GlyphidBite.about(table, jawLeft, 0.0f, 1.0f, 0.0f, rad(JAW_SNAP)),
                GlyphidBite.about(table, jawLeft, 1.0f, 0.0f, 0.0f, rad(JAW_SNAP)),
                GlyphidBite.about(table, jawRight, 0.0f, 1.0f, 0.0f, rad(-JAW_SNAP)),
                GlyphidBite.about(table, jawRight, 1.0f, 0.0f, 0.0f, rad(JAW_SNAP)));
    }

    /**
     * One clip per combination of plates still attached, keyed by the same bits {@link EntityGlyphid#armor()}
     * carries.
     */
    private static void armour(NodeTable table, Map<String, GltfAnimation> clips, int armorFront,
                               int armorLeft, int armorRight, int leftArmor, int rightArmor) {
        int[] plates = { armorFront, armorLeft, armorRight, leftArmor, rightArmor };

        for (int bits = 0; bits < EntityGlyphid.FULL_ARMOR; bits++) {
            List<PoseDriver> drivers = new ArrayList<>(ARMOR_BITS);
            for (int bit = 0; bit < ARMOR_BITS; bit++) {
                if ((bits & (1 << bit)) == 0) {
                    drivers.add(NodeHide.of(table, plates[bit]));
                }
            }
            clips.put(armour(bits), GltfAnimation.procedural(armour(bits),
                    drivers.toArray(new PoseDriver[0])));
        }
    }

    /** The clip name for a set of armour bits; {@link EntityGlyphid#FULL_ARMOR} has none. */
    public static String armour(int bits) {
        return "armor" + bits;
    }

    /** Move a body transform into the mesh's own frame. */
    public static Matrix4f mount(Matrix4f matrix, float scale) {
        return matrix.translate(0.0f, -1.5078125f, 0.0f)
                .rotateX((float) Math.PI)
                .translate(0.0f, -1.5f, 0.0f)
                .scale(scale);
    }

    private static NodeOscillate swing(NodeTable table, int slot, float axisX, float axisY, float axisZ,
                                       double baseDegrees, double amplitudeDegrees, float phaseTurns) {
        return NodeOscillate.about(table, slot, axisX, axisY, axisZ, rad(baseDegrees),
                rad(amplitudeDegrees), CYCLE, phaseTurns);
    }

    /**
     * Two sinusoids of one frequency are a third: {@code A sin(t + a) + B sin(t + b)} written back as an amplitude
     * and a phase, both in the units the drivers take (degrees and turns).
     */
    private static float[] combine(double ampA, float turnsA, double ampB, float turnsB) {
        double sine = ampA * Math.cos(TAU * turnsA) + ampB * Math.cos(TAU * turnsB);
        double cosine = ampA * Math.sin(TAU * turnsA) + ampB * Math.sin(TAU * turnsB);
        return new float[] { (float) Math.hypot(sine, cosine), (float) (Math.atan2(cosine, sine) / TAU) };
    }

    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }
}
