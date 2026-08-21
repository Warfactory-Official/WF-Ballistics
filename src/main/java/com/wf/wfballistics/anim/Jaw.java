package com.wf.wfballistics.anim;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/**
 * A gripper jaw: a part that swings open about a hinge rather than turning continuously like a
 * {@link Rotor}. What holds a crate under a drone and lets go of it.
 *
 * <p>The hinge and the swing direction are both read off the mesh by {@link Bones} rather than declared,
 * so a new airframe's claws work by being shaped like claws.
 *
 * @param model        the part model this jaw draws
 * @param hinge        the point it pivots about, model units: the top of the jaw at its inboard edge
 * @param axis         the hinge axis, oriented so a <em>positive</em> angle swings the jaw open
 * @param openRadians  how far it swings when fully open
 */
public record Jaw(ResourceLocation model, Vector3f hinge, Vector3f axis, float openRadians) {

    public Jaw(ResourceLocation model, Vector3f hinge, Vector3f axis, float openRadians) {
        this.model = model;
        this.hinge = new Vector3f(hinge);
        this.axis = new Vector3f(axis).normalize();
        this.openRadians = openRadians;
    }

    /**
     * @param open 0 for shut around the payload, 1 for fully open
     */
    public float angle(float open) {
        return open * openRadians;
    }
}
