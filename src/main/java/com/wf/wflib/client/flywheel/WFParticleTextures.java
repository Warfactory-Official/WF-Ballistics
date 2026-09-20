package com.wf.wflib.client.flywheel;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;

/** Textures the instanced particles sample directly. */
public final class WFParticleTextures {

    public static final ResourceLocation PARTICLE_BASE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/particle_base.png");

    /** Gas and mist. */
    public static final ResourceLocation MIST_SOFT =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/mist_soft.png");

    /** Fire's own instance shaders. */
    public static final ResourceLocation FLAME_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/flame.vert");

    public static final ResourceLocation FLAME_CULL =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/cull/flame.glsl");

    /** Debris's own vertex shader. */
    public static final ResourceLocation DEBRIS_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/debris.vert");

    /** Ash's own vertex shader. */
    public static final ResourceLocation ASH_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/ash.vert");

    /** The missile exhaust's own shaders. */
    public static final ResourceLocation EXHAUST_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/exhaust.vert");

    public static final ResourceLocation EXHAUST_CULL =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/cull/exhaust.glsl");

    /** The torpedo wake's own vertex shader. */
    public static final ResourceLocation WAKE_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/wake.vert");

    /** The foam sprite: a raft of bubbles rather than a soft blob, which is what reads as foam. */
    public static final ResourceLocation FOAM =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/foam.png");

    /** Blast foam's own shaders. */
    public static final ResourceLocation FOAM_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/foam.vert");

    public static final ResourceLocation FOAM_CULL =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/cull/foam.glsl");

    /**
     * GemRender's own cull shaders, borrowed rather than copied: what a particle's bounding sphere has to be is the
     * library's business and does not change because the vertex shader did.
     */
    public static final ResourceLocation MESH_CULL =
            ResourceLocation.fromNamespaceAndPath("gemrender", "instance/cull/particle_mesh.glsl");

    public static final ResourceLocation BILLBOARD_CULL =
            ResourceLocation.fromNamespaceAndPath("gemrender", "instance/cull/particle.glsl");

    private WFParticleTextures() {
    }
}
