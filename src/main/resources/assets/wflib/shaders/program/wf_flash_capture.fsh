#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D SnapSampler;
uniform float Capture;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    vec3 snap = texture(SnapSampler, texCoord).rgb;
    fragColor = vec4(mix(snap, scene, Capture), 1.0);
}
