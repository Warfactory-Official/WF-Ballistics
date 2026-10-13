#version 150

// White = retina saturation (veil + dazzle blur); After = positive afterimage of the frame captured at the flash.

uniform sampler2D DiffuseSampler;
uniform sampler2D SnapSampler;
uniform vec2 OutSize;
uniform float White;
uniform float After;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 px = (2.0 + 14.0 * White) / OutSize;
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    vec3 blur = (texture(DiffuseSampler, texCoord + vec2(px.x, 0.0)).rgb
            + texture(DiffuseSampler, texCoord - vec2(px.x, 0.0)).rgb
            + texture(DiffuseSampler, texCoord + vec2(0.0, px.y)).rgb
            + texture(DiffuseSampler, texCoord - vec2(0.0, px.y)).rgb
            + texture(DiffuseSampler, texCoord + px * 0.7).rgb
            + texture(DiffuseSampler, texCoord - px * 0.7).rgb) / 6.0;
    float dazzle = clamp(White * 1.6 + After * 0.4, 0.0, 1.0);
    vec3 col = mix(scene, blur, dazzle);

    vec3 snap = texture(SnapSampler, texCoord).rgb;
    float burn = dot(snap, vec3(0.299, 0.587, 0.114));
    col += vec3(1.0, 0.96, 0.86) * burn * burn * After;

    col = mix(col, vec3(1.0), White);
    fragColor = vec4(min(col, vec3(1.0)), 1.0);
}
