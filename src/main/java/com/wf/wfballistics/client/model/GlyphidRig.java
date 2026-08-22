package com.wf.wfballistics.client.model;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import org.joml.Matrix4f;

/**
 * The two parts of drawing a glyphid that both tiers do identically: getting into the frame the mesh was
 * authored in, and hanging the twenty-seven posed parts off a body transform.
 *
 * <p>Shared rather than copied because a glyphid with a body and a glyphid without one must be the same
 * animal on screen — the sim tier's whole claim is that you cannot tell which is which — and two copies of a
 * transform chain containing the constant {@code 1.5078125} are two copies that will eventually disagree.
 * What is <em>not</em> shared is everything only a body has: the flight lean, the corpse roll, the nuclear
 * swell and the bite. Those stay in {@code GlyphidVisual}, because a record does none of them.
 */
public final class GlyphidRig {

    private GlyphidRig() {
    }

    /**
     * Move a body transform into the mesh's own frame.
     *
     * <p>Written the long way round — flip, drop, flip back — rather than as the single rotation the three
     * collapse to. The mesh is authored in the frame a vanilla mob model lives in, which is upside down and
     * offset by the {@code 1.5078125} a living renderer subtracts; writing each step out means the numbers
     * here are the ones the mesh was built against.
     */
    public static Matrix4f mount(Matrix4f matrix, float scale) {
        return matrix.translate(0.0f, -1.5078125f, 0.0f)
                .rotateX((float) Math.PI)
                .translate(0.0f, -1.5f, 0.0f)
                .scale(scale);
    }

    /**
     * Write every part of one glyphid.
     *
     * @param bite  the jaw poses for this frame, from {@link GlyphidPoses#bite}
     * @param walk  the limb poses for this frame, from {@link GlyphidPoses#walk}
     * @param armor the plate bits; a plate that has been knocked off is not drawn, which is the whole of the
     *              armour readout — how battered a glyphid looks is how much damage it is still turning away
     */
    public static void place(TransformedInstance[] parts, Matrix4f root, Matrix4f[] bite, Matrix4f[] walk,
                             byte armor, int light) {
        for (int i = 0; i < GlyphidPoses.JAW_FIRST; i++) {
            set(parts[i], root, light);
        }
        for (int i = 0; i < GlyphidPoses.JAW_COUNT; i++) {
            set(parts[GlyphidPoses.JAW_FIRST + i], root, bite[i], light);
        }
        for (int i = 0; i < GlyphidPoses.WALK_COUNT; i++) {
            set(parts[GlyphidPoses.WALK_FIRST + i], root, walk[i], light);
        }
        if (armor == EntityGlyphid.FULL_ARMOR) {
            return;
        }
        for (int bit = 0; bit < GlyphidPoses.ARMOR_PART.length; bit++) {
            if ((armor & (1 << bit)) == 0) {
                parts[GlyphidPoses.ARMOR_PART[bit]].setZeroTransform();
                parts[GlyphidPoses.ARMOR_PART[bit]].setChanged();
            }
        }
    }

    /**
     * Collapse every part to nothing: a slot in an instance pool that no glyphid is using this frame.
     */
    public static void hide(TransformedInstance[] parts) {
        for (TransformedInstance part : parts) {
            part.setZeroTransform();
            part.setChanged();
        }
    }

    private static void set(TransformedInstance instance, Matrix4f root, int light) {
        instance.pose.set(root);
        instance.light(light);
        instance.setChanged();
    }

    private static void set(TransformedInstance instance, Matrix4f root, Matrix4f local, int light) {
        // Straight into the instance's own matrix: building one here and handing it to setTransform would
        // allocate a Matrix4f per part per glyphid per frame only to copy it into this same field.
        instance.pose.set(root).mul(local);
        instance.light(light);
        instance.setChanged();
    }
}
