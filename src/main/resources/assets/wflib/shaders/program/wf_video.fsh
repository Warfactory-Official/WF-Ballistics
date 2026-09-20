#version 150

// Everything between the sensor and the screen that is not compression: gain noise, chroma loss, line
// tearing, the lens, and the tube.
//
// Split from the macroblock pass because the two have different causes and should not scale together. Noise
// is the sensor working hard and is there on a perfect link; blocking is the encoder running out of bits and
// is not. Putting them in one shader makes it impossible to have a clean, grainy picture, which is what a
// good feed from a cheap camera actually looks like.

uniform sampler2D DiffuseSampler;

uniform vec2 InSize;
uniform float Degrade;
uniform float FeedTime;
// Per-mode, from the pass. Optical is already flatter than the eye; thermal and low light have no colour of
// their own to lose, so their chains pass 1.0 and let the palette decide.
uniform float Saturation;
uniform float NoiseFloor;

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float amount = clamp(Degrade, 0.0, 1.0);
    vec2 uv = texCoord;

    // Line tearing. A dropped packet costs whole scanlines, not pixels, and they hold their offset for a few
    // frames rather than jittering -- which is why the time term is quantised.
    float line = floor(uv.y * InSize.y);
    float torn = hash(vec2(line, floor(FeedTime * 3.0)));
    if (torn < amount * 0.06) {
        uv.x += (hash(vec2(line, 7.0)) - 0.5) * 0.08 * amount;
    }
    vec3 c = texture(DiffuseSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;

    // Chroma goes first. Every codec spends its bits on luminance because that is what the eye reads, so a
    // failing feed desaturates toward grey well before it stops being legible.
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, Saturation * mix(1.0, 0.2, amount));

    // Sensor noise: white, per pixel, per frame. Present at NoiseFloor even on a perfect link, because a
    // camera that shows a completely clean image is a render, not a camera.
    float n = hash(uv * InSize + vec2(FeedTime * 137.0, FeedTime * 71.0)) - 0.5;
    c += n * (NoiseFloor + 0.22 * amount);

    // Lens vignette and a faint scanline. Optics and display, not link: neither scales with Degrade.
    vec2 d = uv - 0.5;
    c *= 1.0 - 0.7 * dot(d, d);
    c *= 1.0 - 0.05 * step(0.5, fract(uv.y * InSize.y * 0.5));

    fragColor = vec4(clamp(c, vec3(0.0), vec3(1.0)), 1.0);
}
