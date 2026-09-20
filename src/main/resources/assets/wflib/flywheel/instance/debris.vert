#include "gemrender:particle.glsl"

// A chunk of the world: the block bake already put shading, tint and light in the vertex, so the style
// does not replace them.

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec3 center = i.origin + gemrender_particlePosition(p, s, age);
    float size = gemrender_particleAlive(p, age) ? gemrender_particleSize(p, s, unitAge) : 0.0;

    float turning = min(age, p.restAge);
    float yaw = p.spinPhase + s.spinRate * turning;
    float roll = p.tintScale + s.spinRate * 0.7 * turning;

    float cosYaw = cos(yaw);
    float sinYaw = sin(yaw);
    float cosRoll = cos(roll);
    float sinRoll = sin(roll);

    mat3 aboutY = mat3(vec3(cosYaw, 0.0, -sinYaw), vec3(0.0, 1.0, 0.0), vec3(sinYaw, 0.0, cosYaw));
    mat3 aboutZ = mat3(vec3(cosRoll, sinRoll, 0.0), vec3(-sinRoll, cosRoll, 0.0), vec3(0.0, 0.0, 1.0));
    mat3 basis = aboutY * aboutZ;

    flw_vertexPos = vec4(center + basis * (flw_vertexPos.xyz * size), 1.0);
    flw_vertexNormal = basis * flw_vertexNormal;
}
