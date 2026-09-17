#include "gemrender:particle.glsl"

// Settled foam spreads past its own size (FOAM_SPREAD in foam.vert).

const float FOAM_SPREAD = 1.45;

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;

    if (!gemrender_particleAlive(p, age)) {
        radius = -1e18;
        return;
    }

    center = i.origin + gemrender_particlePosition(p, s, age);
    radius = radius * gemrender_particleSize(p, s, gemrender_particleUnitAge(p, age)) * FOAM_SPREAD;
}
