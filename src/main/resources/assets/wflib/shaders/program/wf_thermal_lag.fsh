#version 150

// Microbolometer artefacts on the palettised picture: thermal time constant (ghost trail on pan), column/row
// fixed-pattern noise drifting between non-uniformity corrections, temporal NETD noise, and the NUC shutter:
// picture frozen for a few ticks every NUC_PERIOD, pattern re-zeroed after.
#define NUC_PERIOD 900.0
#define NUC_FREEZE 6.0
#define GHOST 0.55

uniform sampler2D DiffuseSampler;
uniform sampler2D PrevSampler;

uniform vec2 InSize;
uniform float FeedTime;

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 prev = texture(PrevSampler, texCoord).rgb;
    float cycle = FeedTime / NUC_PERIOD;
    if (fract(cycle) * NUC_PERIOD < NUC_FREEZE) {
        fragColor = vec4(prev, 1.0);
        return;
    }
    vec3 now = texture(DiffuseSampler, texCoord).rgb;
    vec3 c = mix(now, prev, GHOST);

    vec2 px = floor(texCoord * InSize);
    float epoch = floor(cycle);
    float drift = fract(cycle);
    float fpn = (hash(vec2(px.x, epoch)) - 0.5) * 0.05 + (hash(vec2(epoch, px.y)) - 0.5) * 0.02;
    float netd = hash(px + vec2(FeedTime * 13.0, FeedTime * 7.0)) - 0.5;
    c += vec3(fpn * (0.3 + drift) + netd * 0.04);
    fragColor = vec4(clamp(c, vec3(0.0), vec3(1.0)), 1.0);
}
