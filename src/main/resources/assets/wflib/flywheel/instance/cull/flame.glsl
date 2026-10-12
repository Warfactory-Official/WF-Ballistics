#include "gemrender:particle.glsl"

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;

    if (!gemrender_particleAlive(p, age)) {
        radius = -1e18;
        return;
    }

    // flame.vert: quad spans y in [-0.1, 1.4], x in [-0.375, 0.375] of sizeScale.
    center = i.origin + gemrender_particlePosition(p, s, age) + vec3(0.0, 0.65 * p.sizeScale, 0.0);
    radius = 0.85 * p.sizeScale;
}
