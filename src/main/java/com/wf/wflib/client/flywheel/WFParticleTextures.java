package com.wf.wflib.client.flywheel;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;

/** Textures the instanced particles sample directly. */
public final class WFParticleTextures {

    public static final ResourceLocation PARTICLE_BASE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/particle_base.png");

    public static final ResourceLocation BLOOD_DROP =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/blood_drop.png");

    public static final ResourceLocation BLOOD_SPLAT =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/blood_splat.png");

    public static final ResourceLocation MIST_SOFT =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/mist_soft.png");

    /** 2x2 atlas of billowed puffs; cell per particle in {@link #SMOKE_VERTEX}. */
    public static final ResourceLocation SMOKE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/smoke.png");

    public static final ResourceLocation SMOKE_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/smoke.vert");

    /** 2x2 atlas of flame tongues; cell per particle in {@link #FLAME_VERTEX}. */
    public static final ResourceLocation FLAME =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/flame.png");

    public static final ResourceLocation FLAME_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/flame.vert");

    public static final ResourceLocation FLAME_CULL =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/cull/flame.glsl");

    public static final ResourceLocation DEBRIS_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/debris.vert");

    public static final ResourceLocation ASH_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/ash.vert");

    public static final ResourceLocation EXHAUST_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/exhaust.vert");

    public static final ResourceLocation EXHAUST_CULL =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/cull/exhaust.glsl");

    public static final ResourceLocation WAKE_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/wake.vert");

    /** The foam sprite: a raft of bubbles rather than a soft blob, which is what reads as foam. */
    public static final ResourceLocation FOAM =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/particle/foam.png");

    public static final ResourceLocation FOAM_VERTEX =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/foam.vert");

    public static final ResourceLocation FOAM_CULL =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "instance/cull/foam.glsl");

    /** Borrowed from GemRender's own namespace, not copied: bounding-sphere math is the library's business. */
    public static final ResourceLocation MESH_CULL =
            ResourceLocation.fromNamespaceAndPath("gemrender", "instance/cull/particle_mesh.glsl");

    public static final ResourceLocation BILLBOARD_CULL =
            ResourceLocation.fromNamespaceAndPath("gemrender", "instance/cull/particle.glsl");

    private WFParticleTextures() {
    }
}
