// Automatic brightness control state: mean amplified signal, eased at abc_rate.
uniform sampler2D _nv_flux;
uniform sampler2D _nv_prev;
uniform float _nv_lod;
uniform float _nv_gain;
uniform float _nv_ease;

layout(location = 0) out float _nv_adapt;

void main() {
    float mean = textureLod(_nv_flux, vec2(0.5), _nv_lod).r * _nv_gain;
    _nv_adapt = mix(texelFetch(_nv_prev, ivec2(0), 0).r, mean, _nv_ease);
}
