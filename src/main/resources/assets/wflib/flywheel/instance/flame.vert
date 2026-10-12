#include "gemrender:particle.glsl"

// Upright tongue: base at the particle, 2x2 atlas cell + mirror from spinPhase (uniform in [0, 2pi)).
// Quad extent mirrored in cull/flame.glsl.

const float FLAME_WIDTH = 0.75;
const float FLAME_HEIGHT = 1.5;

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;

    vec3 center = i.origin + gemrender_particlePosition(p, s, age);
    float size = gemrender_particleAlive(p, age) ? p.sizeScale : 0.0;
    float near = gemrender_nearFade(center, flw_cameraPos, size);
    size *= step(1e-4, near);

    vec3 camRight = vec3(flw_viewInverse[0]);
    vec3 level = vec3(camRight.x, 0.0, camRight.z);
    vec3 right = dot(level, level) > 1e-4 ? normalize(level) : camRight;
    vec3 up = vec3(0.0, 1.0, 0.0);

    float cell = floor(fract(p.spinPhase * 7.13) * 4.0);
    float mirror = fract(p.spinPhase * 3.37) < 0.5 ? -1.0 : 1.0;
    // Tongue shortens as it burns out.
    float height = FLAME_HEIGHT * (1.0 - 0.35 * unitAge);
    vec2 shaped = vec2(corner.x * FLAME_WIDTH * mirror, (corner.y + 0.5) * height - 0.1);
    flw_vertexTexCoord = (vec2(mod(cell, 2.0), floor(cell / 2.0)) + flw_vertexTexCoord) / 2.0;

    float r = clamp(1.0 - unitAge * 0.3, 0.0, 1.0);
    float g = clamp(0.45 * (1.0 - unitAge) + 0.1, 0.0, 1.0);
    float b = 0.05;

    float whiteOut = clamp((unitAge - s.coolFloor) / max(s.coolSpan, 1e-6), 0.0, 1.0);
    vec3 tint = vec3(r, g, b);
    tint += (vec3(1.0) - tint) * whiteOut;

    float alpha = s.alphaScale * pow(1.0 - unitAge, s.alphaFalloff);

    flw_vertexPos = vec4(center + (shaped.x * right + shaped.y * up) * size, 1.0);
    flw_vertexNormal = -vec3(flw_viewInverse[2]);
    flw_vertexColor = vec4(clamp(tint * p.tintScale, 0.0, 1.0), clamp(alpha, 0.0, 1.0) * near);
    flw_vertexOverlay = gemrender_particleOverlay(s.glow);
    flw_vertexLight = s.light;
}
