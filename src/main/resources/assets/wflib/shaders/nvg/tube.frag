// Tube image intensity per screen pixel; -1 outside every tube.
#define ABC_TARGET 0.45

uniform sampler2D _nv_flux;
uniform sampler2D _nv_adapt;
uniform sampler2D _nv_history;
uniform vec4 _nv_tubes[4];
uniform int _nv_tubeCount;
uniform vec2 _nv_screen;
uniform float _nv_gain;
uniform float _nv_gainFrac;
uniform float _nv_snr;
uniform float _nv_ebi;
uniform float _nv_halo;
uniform float _nv_haloLod;
uniform float _nv_autogate;
uniform float _nv_distortion;
uniform float _nv_vignette;
uniform float _nv_honeycomb;
uniform float _nv_persistence;
uniform uint _nv_frame;

layout(location = 0) out float _nv_out;

uint _nv_hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v.x ^ v.y ^ v.z;
}

float _nv_rand(uvec3 v) {
    return float(_nv_hash(v) & 0xFFFFFFu) / 16777216.0;
}

void main() {
    vec2 q = (gl_FragCoord.xy - 0.5 * _nv_screen) / _nv_screen.y;
    int tube = -1;
    vec2 d = vec2(0.0);
    for (int i = 0; i < _nv_tubeCount; i++) {
        vec2 dd = (q - _nv_tubes[i].xy) / _nv_tubes[i].z;
        if (tube < 0 && dot(dd, dd) < 1.0) {
            tube = i;
            d = dd;
        }
    }
    if (tube < 0) {
        _nv_out = -1.0;
        return;
    }
    float rr = dot(d, d);
    vec2 sq = _nv_tubes[tube].xy + d * (1.0 + _nv_distortion * rr) * _nv_tubes[tube].z;
    vec2 uv = sq * vec2(_nv_screen.y / _nv_screen.x, 1.0) + 0.5;

    float adapt = texelFetch(_nv_adapt, ivec2(0), 0).r;
    float gain = _nv_gain * ABC_TARGET / max(adapt, ABC_TARGET);
    float y = 1.0 - exp(-texture(_nv_flux, uv).r * gain);

    // Halo round bright sources; without autogating the tube blooms wider.
    float h = textureLod(_nv_flux, uv, _nv_haloLod).r * gain;
    float glow = smoothstep(1.0, 6.0, h) * _nv_halo + (1.0 - _nv_autogate) * smoothstep(2.0, 12.0, h) * _nv_halo;
    y += glow * (1.0 - y);

    uvec3 seed = uvec3(uvec2(gl_FragCoord.xy), _nv_frame);
    float u1 = max(_nv_rand(seed), 1.0e-6);
    float u2 = _nv_rand(seed + uvec3(7u, 13u, 0u));
    float gauss = sqrt(-2.0 * log(u1)) * cos(6.2831853 * u2);
    y += gauss * sqrt(y + _nv_ebi) * 2.5 / _nv_snr;
    if (_nv_rand(seed + uvec3(31u, 17u, 3u)) < _nv_ebi * 0.004 * _nv_gainFrac) {
        y += 0.8;
    }

    // Microchannel plate hex pattern, ~5 px cells.
    vec2 cell = vec2(5.0, 8.66);
    vec2 a = mod(gl_FragCoord.xy, cell) - 0.5 * cell;
    vec2 b = mod(gl_FragCoord.xy - 0.5 * cell, cell) - 0.5 * cell;
    float g = min(length(a), length(b)) / 5.0;
    y *= 1.0 - _nv_honeycomb * smoothstep(0.38, 0.5, g);

    y *= 1.0 - _nv_vignette * rr * rr;
    y = clamp(y, 0.0, 1.5);
    _nv_out = max(y, texelFetch(_nv_history, ivec2(gl_FragCoord.xy), 0).r * _nv_persistence);
}
