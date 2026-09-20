#include "gemrender:particle.glsl"

// A rocket motor's exhaust. cool(floor, span) is two durations in SECONDS (glow, then stop glowing),
// and the plume widens as sqrt(age).

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;
    vec3 center = i.origin + gemrender_particlePosition(p, s, age);

    float spread = sqrt(unitAge);
    float size = gemrender_particleAlive(p, age)
            ? p.sizeScale * (s.size0 + s.sizeRate * spread)
            : 0.0;

    // 0 while the gas is still burning, 1 once it is only smoke.
    float burnt = clamp((age - s.coolFloor) / max(s.coolSpan, 1e-6), 0.0, 1.0);

    vec3 flame = mix(s.tint, vec3(1.0), 0.55);
    vec3 smoke = mix(vec3(0.95, 0.95, 0.96), vec3(0.52, 0.52, 0.56), pow(unitAge, 1.5));

    // Denser while it is still burning than after: the flame is a solid core, the smoke is not.
    float alpha = s.alphaScale * pow(1.0 - unitAge, s.alphaFalloff) * mix(1.8, 1.0, burnt);
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
    flw_vertexColor = vec4(clamp(mix(flame, smoke, burnt) * p.tintScale, 0.0, 1.0),
                           clamp(alpha * ramp, 0.0, 1.0));
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = mix(vec2(1.0), s.light, burnt);
}
