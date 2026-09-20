#include "gemrender:particle.glsl"

// Blast foam: aerated water, white and thinning toward the sea. cool(floor, span) is two durations in
// SECONDS, and settling also lays the quad flat on the surface and spreads it.

const float FOAM_FLATTEN = 0.22;
const float FOAM_SPREAD = 1.45;

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;
    vec3 center = i.origin + gemrender_particlePosition(p, s, age);
    float size = gemrender_particleAlive(p, age) ? gemrender_particleSize(p, s, unitAge) : 0.0;

    float settled = clamp((age - s.coolFloor) / max(s.coolSpan, 1e-6), 0.0, 1.0);

    float angle = p.spinPhase + s.spinRate * age;
    float cosine = cos(angle);
    float sine = sin(angle);

    vec3 right = vec3(flw_viewInverse[0]);
    vec3 up = vec3(flw_viewInverse[1]);
    vec3 spunRight = right * cosine + up * sine;
    vec3 spunUp = up * cosine - right * sine;

    vec3 offset = (corner.x * spunRight + corner.y * spunUp) * size;
    offset.y *= mix(1.0, FOAM_FLATTEN, settled);
    offset.xz *= mix(1.0, FOAM_SPREAD, settled);

    // Denser while it is still churned than once the air has risen out of it.
    float alpha = s.alphaScale * pow(1.0 - unitAge, s.alphaFalloff) * mix(1.4, 0.85, settled);
    float ramp = s.fadeIn > 1e-4 ? min(unitAge / s.fadeIn, 1.0) : 1.0;

    flw_vertexPos = vec4(center + offset, 1.0);
    flw_vertexNormal = -vec3(flw_viewInverse[2]);
    flw_vertexColor = vec4(clamp(mix(vec3(1.0), mix(s.tint, vec3(0.90, 0.96, 1.0), 0.35), settled)
                                 * p.tintScale, 0.0, 1.0),
                           clamp(alpha * ramp, 0.0, 1.0));
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = s.light;
}
