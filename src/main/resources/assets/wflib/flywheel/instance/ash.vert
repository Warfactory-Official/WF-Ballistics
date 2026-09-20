#include "gemrender:particle.glsl"

// A settling cinder: a flat scrap in the XZ plane, not a billboard, and alphaFalloff is the fraction
// of life spent fading.

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;

    vec3 center = i.origin + gemrender_particlePosition(p, s, age);
    float size = gemrender_particleAlive(p, age) ? gemrender_particleSize(p, s, unitAge) : 0.0;

    float landed = step(p.contactAge, age);
    float angle = p.spinPhase + s.spinRate * min(age, p.restAge);
    center.y += landed * 0.02;

    float cosine = cos(angle);
    float sine = sin(angle);
    vec3 right = vec3(cosine, 0.0, sine);
    vec3 forward = vec3(-sine, 0.0, cosine);

    flw_vertexPos = vec4(center + (corner.x * right + corner.y * forward) * size, 1.0);
    flw_vertexNormal = vec3(0.0, 1.0, 0.0);

    float fade = max(s.alphaFalloff, 1e-4);
    flw_vertexColor = vec4(clamp(s.tint * p.tintScale, 0.0, 1.0),
                           s.alphaScale * clamp((1.0 - unitAge) / fade, 0.0, 1.0));
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = s.light;
}
