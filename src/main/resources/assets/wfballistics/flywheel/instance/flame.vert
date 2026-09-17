#include "gemrender:particle.glsl"

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);

    float age = flw_renderSeconds - p.spawnTime;
    float unitAge = gemrender_particleUnitAge(p, age);

    vec2 corner = flw_vertexPos.xy;

    vec3 center = i.origin + gemrender_particlePosition(p, s, age);
    float size = gemrender_particleAlive(p, age) ? p.sizeScale : 0.0;

    vec3 right = vec3(flw_viewInverse[0]);
    vec3 up = vec3(flw_viewInverse[1]);

    float r = clamp(1.0 - unitAge * 0.3, 0.0, 1.0);
    float g = clamp(0.6 * (1.0 - unitAge) + 0.1, 0.0, 1.0);
    float b = 0.05;

    float whiteOut = clamp((unitAge - s.coolFloor) / max(s.coolSpan, 1e-6), 0.0, 1.0);
    vec3 tint = vec3(r, g, b);
    tint += (vec3(1.0) - tint) * whiteOut;

    float alpha = s.alphaScale * pow(1.0 - unitAge, s.alphaFalloff);

    flw_vertexPos = vec4(center + (corner.x * right + corner.y * up) * size, 1.0);
    flw_vertexNormal = -vec3(flw_viewInverse[2]);
    flw_vertexColor = vec4(clamp(tint * p.tintScale, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = s.light;
}
