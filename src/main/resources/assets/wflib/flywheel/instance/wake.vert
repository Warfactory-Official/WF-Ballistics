#include "gemrender:particle.glsl"

// A torpedo's wake: entrained air, so it starts white and thins toward the water rather than darkening.
// cool(floor, span) is two durations in SECONDS.

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;
    vec3 center = i.origin + gemrender_particlePosition(p, s, age);

    float size = gemrender_particleAlive(p, age)
            ? p.sizeScale * (s.size0 + s.sizeRate * unitAge)
            : 0.0;

    // 0 while it is still solid froth, 1 once it is loose bubbles.
    float aerated = clamp((age - s.coolFloor) / max(s.coolSpan, 1e-6), 0.0, 1.0);

    vec3 froth = vec3(1.0);
    vec3 thinned = mix(s.tint, vec3(0.88, 0.94, 1.0), 0.45);

    float alpha = s.alphaScale * pow(1.0 - unitAge, s.alphaFalloff) * mix(1.5, 1.0, aerated);
    float ramp = s.fadeIn > 1e-4 ? min(unitAge / s.fadeIn, 1.0) : 1.0;

    float angle = p.spinPhase + s.spinRate * age;
    float cosine = cos(angle);
    float sine = sin(angle);

    vec3 right = vec3(flw_viewInverse[0]);
    vec3 up = vec3(flw_viewInverse[1]);
    vec3 spunRight = right * cosine + up * sine;
    vec3 spunUp = up * cosine - right * sine;

    flw_vertexPos = vec4(center + (corner.x * spunRight + corner.y * spunUp) * size, 1.0);
    flw_vertexNormal = -vec3(flw_viewInverse[2]);
    flw_vertexColor = vec4(clamp(mix(froth, thinned, aerated) * p.tintScale, 0.0, 1.0),
                           clamp(alpha * ramp, 0.0, 1.0));
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = s.light;
}
