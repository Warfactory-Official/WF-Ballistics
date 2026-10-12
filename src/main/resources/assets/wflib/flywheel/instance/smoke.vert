#include "gemrender:particle.glsl"

// gemrender:particle.vert + per-particle variety keyed off spinPhase (uniform in [0, 2pi)):
// 2x2 atlas cell, area-preserving stretch 0.82..1.22, spin direction.

const float SMOKE_GRID = 2.0;

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;

    vec3 center = i.origin + gemrender_particlePosition(p, s, age);
    float size = gemrender_particleAlive(p, age) ? gemrender_particleSize(p, s, unitAge) : 0.0;
    float near = gemrender_nearFade(center, flw_cameraPos, size) * gemrender_cullFade(center, flw_cameraPos, s.cull);
    size *= step(1e-4, near);

    float cell = floor(fract(p.spinPhase * 7.13) * SMOKE_GRID * SMOKE_GRID);
    float stretch = mix(0.82, 1.22, fract(p.spinPhase * 13.71));
    float turn = fract(p.spinPhase * 3.37) < 0.5 ? -1.0 : 1.0;
    corner *= vec2(stretch, 1.0 / stretch);

    float angle = p.spinPhase + turn * s.spinRate * age;
    float cosine = cos(angle);
    float sine = sin(angle);

    vec3 right = vec3(flw_viewInverse[0]);
    vec3 up = vec3(flw_viewInverse[1]);
    vec3 spunRight = right * cosine + up * sine;
    vec3 spunUp = up * cosine - right * sine;

    flw_vertexPos = vec4(center + (corner.x * spunRight + corner.y * spunUp) * size, 1.0);
    flw_vertexTexCoord = (vec2(mod(cell, SMOKE_GRID), floor(cell / SMOKE_GRID)) + flw_vertexTexCoord) / SMOKE_GRID;
    flw_vertexNormal = -vec3(flw_viewInverse[2]);
    vec4 color = gemrender_particleColor(p, s, unitAge);
    flw_vertexColor = vec4(color.rgb, color.a * near);
    flw_vertexOverlay = gemrender_particleOverlay(gemrender_particleHeat(s, unitAge));
    flw_vertexLight = gemrender_particleLight(p, s);
}
