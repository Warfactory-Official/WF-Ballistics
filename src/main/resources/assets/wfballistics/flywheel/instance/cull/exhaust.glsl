#include "gemrender:particle.glsl"

// exhaust.vert grows as sqrt(unitAge), which the library's sphere does not assume.

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;

    if (!gemrender_particleAlive(p, age)) {
        radius = -1e18;
        return;
    }

    float spread = sqrt(gemrender_particleUnitAge(p, age));

    center = i.origin + gemrender_particlePosition(p, s, age);
    radius = radius * p.sizeScale * (s.size0 + s.sizeRate * spread);
}
