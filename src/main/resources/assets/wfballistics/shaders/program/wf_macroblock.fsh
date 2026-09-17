#version 150

// Block-transform compression artifacts.
//
// Not a mosaic filter. A mosaic destroys detail everywhere equally, which looks like a filter; a video codec
// running out of bits keeps detail in the blocks it can afford and collapses the rest to their average, which
// looks like a bad link. The difference is the per-block decision below, and it is the whole effect.

uniform sampler2D DiffuseSampler;

uniform vec2 InSize;
// 0 = perfect datalink, 1 = about to drop. Driven from the feed's link quality by CameraTarget, so this is
// the same number the OSD draws as signal bars.
uniform float Degrade;
uniform float FeedTime;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float amount = clamp(Degrade, 0.0, 1.0);

    // Macroblocks grow as the bitrate falls. 8 pixels is the classic transform block; 32 is what you get
    // when the encoder has given up on everything but the coarsest structure.
    float block = mix(8.0, 32.0, amount);
    vec2 blockSize = vec2(block) / InSize;
    vec2 blockId = floor(texCoord / blockSize);
    vec2 blockUv = (blockId + 0.5) * blockSize;

    vec4 detail = texture(DiffuseSampler, texCoord);

    // The block's DC term: four taps inside it, which is close enough to its mean and four times cheaper
    // than anything that would be closer.
    vec4 dc = 0.25 * (
        texture(DiffuseSampler, blockUv + blockSize * vec2(-0.25, -0.25)) +
        texture(DiffuseSampler, blockUv + blockSize * vec2( 0.25, -0.25)) +
        texture(DiffuseSampler, blockUv + blockSize * vec2(-0.25,  0.25)) +
        texture(DiffuseSampler, blockUv + blockSize * vec2( 0.25,  0.25)));

    // Which blocks lose their detail is per block and stable for a while, not per frame. Blocks that freeze
    // and then catch up are what compression failure actually looks like; blocks that flicker every frame
    // read as noise, which is a different effect that this shader is deliberately not doing.
    float refreshed = hash(blockId + floor(FeedTime * 0.3));
    float flatten = step(refreshed, amount);

    vec4 colour = mix(detail, dc, flatten * amount);

    // Coefficient quantisation: too few levels, so smooth gradients band. Falls from 64 levels to 6.
    float levels = mix(64.0, 6.0, amount);
    colour.rgb = floor(colour.rgb * levels + 0.5) / levels;

    fragColor = vec4(colour.rgb, 1.0);
}
