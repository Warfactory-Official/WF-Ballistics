#version 150

// An uncooled microbolometer, approximated.
//
// The honest caveat first: nothing in this frame knows its own temperature. What arrives here is the colour
// the world was rendered in, and the estimate below is built from that -- luminance for how much the surface
// is emitting or reflecting, plus the red-over-blue excess as a stand-in for how warm it is. It gets the
// cases that matter right (lava, fire, torches and furnaces read as hot; sky, water and snow read as cold;
// stone and grass sit in the middle) and it will be wrong about a red wool block, which will look warm
// because it is red.
//
// The palette is ironbow, which is the one every thermal sight uses: black through purple and red into
// orange, yellow, and white for the hottest thing in frame.

uniform sampler2D DiffuseSampler;
// Entity stencil, rendered by CameraHeatMask against the feed's own depth buffer. Alpha, not colour: a
// black sheep has to read as strongly as a white one, and alpha is 1 wherever a fragment survived the
// cutout whatever it was painted.
uniform sampler2D HeatSampler;

uniform float Degrade;

in vec2 texCoord;

out vec4 fragColor;

vec3 ironbow(float t) {
    t = clamp(t, 0.0, 1.0);
    vec3 c0 = vec3(0.00, 0.00, 0.06);
    vec3 c1 = vec3(0.24, 0.02, 0.44);
    vec3 c2 = vec3(0.74, 0.10, 0.28);
    vec3 c3 = vec3(1.00, 0.46, 0.02);
    vec3 c4 = vec3(1.00, 0.92, 0.36);
    vec3 c5 = vec3(1.00, 1.00, 1.00);
    if (t < 0.2) { return mix(c0, c1, t / 0.2); }
    if (t < 0.4) { return mix(c1, c2, (t - 0.2) / 0.2); }
    if (t < 0.6) { return mix(c2, c3, (t - 0.4) / 0.2); }
    if (t < 0.85) { return mix(c3, c4, (t - 0.6) / 0.25); }
    return mix(c4, c5, (t - 0.85) / 0.15);
}

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;

    // Emitted plus reflected, weighted toward green because that is where the renderer puts most of its
    // luminance, then a warmth term from the red excess over blue.
    float radiance = dot(c, vec3(0.30, 0.45, 0.10));
    float warmth = max(0.0, c.r - c.b);
    // And the other way round. Without this the sky is the hottest thing in every frame -- it is the
    // brightest, so a luminance-only estimate has nowhere else to put it -- which is exactly backwards:
    // open sky is the coldest thing a thermal sight ever looks at. Blue excess is the only signal
    // available for "this is sky or water rather than a surface", so it is the one used.
    float coolness = max(0.0, c.b - c.r);
    float heat = clamp(radiance * 0.62 + warmth * 0.95 - coolness * 0.85, 0.0, 1.0);

    // A body. Everything above this line is inference from colour, which cannot distinguish a cow from the
    // grass behind it; this is the one term that actually knows. Kept as a floor rather than a replacement,
    // so an entity standing in front of a furnace does not read as colder than the furnace -- and applied
    // before the palette, so it takes the same automatic gain and the same degradation as the rest.
    float body = texture(HeatSampler, texCoord).a;
    // Just short of white: the top of the palette stays reserved for genuine fire, so a burning creeper
    // still reads hotter than an unlit one.
    heat = max(heat, body * 0.92);

    // Automatic gain: a thermal sight is always stretching a narrow band of temperatures across the whole
    // display, which is why everything in one looks high contrast whatever the actual scene.
    heat = clamp((heat - 0.06) / 0.80, 0.0, 1.0);
    heat = pow(heat, 0.78);

    // A failing link costs the sensor its calibration before it costs it the picture: the palette compresses
    // toward the middle and detail at both ends goes.
    heat = mix(heat, 0.5 + (heat - 0.5) * 0.6, clamp(Degrade, 0.0, 1.0) * 0.5);

    fragColor = vec4(ironbow(heat), 1.0);
}
