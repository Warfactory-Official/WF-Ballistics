// Photocathode: photon flux at tube resolution. Scene colour is display-encoded => ~linear by ^2.2.
uniform sampler2D _nv_scene;
uniform sampler2D _nv_ir;
uniform vec4 _nv_spectral;
uniform float _nv_irOn;
uniform vec2 _nv_size;

layout(location = 0) out float _nv_flux;

void main() {
    vec2 uv = gl_FragCoord.xy / _nv_size;
    vec3 lin = pow(texture(_nv_scene, uv).rgb, vec3(2.2));
    float flux = dot(lin, _nv_spectral.rgb);
    if (_nv_irOn > 0.5) {
        vec3 ir = texture(_nv_ir, uv).rgb;
        // Chlorophyll: green-dominant albedo reflects NIR strongly.
        float nir = dot(ir, vec3(0.3, 0.4, 0.3)) + 2.0 * max(ir.g - max(ir.r, ir.b), 0.0);
        flux += nir * _nv_spectral.a;
    }
    _nv_flux = flux;
}
