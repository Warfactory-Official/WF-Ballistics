#version 150

// An image intensifier: gain applied to whatever photons arrived.
//
// The saturating exponential is the point. A linear brightness lift washes out the highlights and leaves the
// shadows still black; a real intensifier has enormous gain at the bottom of its range and none at the top,
// so a dark room becomes readable while a torch in frame blooms to a featureless white disc. That asymmetry
// is what makes the mode useful at night and useless at noon, which is the trade it is supposed to present.

uniform sampler2D DiffuseSampler;

uniform float Degrade;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float luma = dot(c, vec3(0.299, 0.587, 0.114));

    // Gain, saturating. Everything above about 0.4 is already white.
    float amplified = 1.0 - exp(-luma * 7.0);

    // Green phosphor. Not a colour choice: the screen is monochrome because the tube is, which is also why
    // this mode cannot tell you what colour anything is.
    vec3 phosphor = vec3(0.16, 1.00, 0.34);

    // Blooming around the brightest parts, faked from the amplified value itself rather than a blur pass.
    float bloom = smoothstep(0.75, 1.0, amplified) * 0.5;
    fragColor = vec4(clamp(phosphor * amplified + vec3(bloom), vec3(0.0), vec3(1.0)), 1.0);
}
