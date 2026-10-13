#version 150

// Image intensifier on a feed or sight picture. Gain on linear light: under a darkness mod a moonlit scene is
// ~0.005 linear, so the curve must reach that; bright sources saturate and bloom.

uniform sampler2D DiffuseSampler;

uniform float Degrade;
uniform float Exposure;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 c = pow(texture(DiffuseSampler, texCoord).rgb, vec3(2.2));
    // Photocathode: red/near-IR heavy.
    float flux = dot(c, vec3(0.6, 0.35, 0.15));
    float amplified = 1.0 - exp(-flux * 250.0 * Exposure);
    vec3 phosphor = vec3(0.35, 1.00, 0.45);
    float bloom = smoothstep(0.8, 1.0, amplified) * 0.4;
    fragColor = vec4(clamp(phosphor * pow(amplified, 0.85) + vec3(bloom), vec3(0.0), vec3(1.0)), 1.0);
}
