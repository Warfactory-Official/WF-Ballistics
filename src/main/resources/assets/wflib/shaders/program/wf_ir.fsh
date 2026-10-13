#version 150

// Active IR (CMOS/SWIR) camera: monochrome near-IR. Scene colour already carries IR lights (IrView.MERGED).
// Foliage reflects NIR strongly; blue sky and water little.

uniform sampler2D DiffuseSampler;

uniform float Degrade;
uniform float Exposure;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 c = pow(texture(DiffuseSampler, texCoord).rgb, vec3(2.2));
    float nir = dot(c, vec3(0.45, 0.4, 0.15)) + 1.5 * max(c.g - max(c.r, c.b), 0.0) - 1.2 * max(c.b - max(c.r, c.g), 0.0);
    float y = 1.0 - exp(-max(nir, 0.0) * 18.0 * Exposure);
    fragColor = vec4(vec3(0.93, 0.96, 1.0) * pow(y, 0.8), 1.0);
}
