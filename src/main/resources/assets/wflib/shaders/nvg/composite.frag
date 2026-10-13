// Tubes over the naked-eye view; housing shadow round them. Blend: src alpha, 1 - src alpha.
uniform sampler2D _nv_tube;
uniform vec4 _nv_tubes[4];
uniform int _nv_tubeCount;
uniform vec2 _nv_screen;
uniform vec3 _nv_phosphor;
uniform float _nv_fade;

layout(location = 0) out vec4 _nv_colour;

void main() {
    float y = texelFetch(_nv_tube, ivec2(gl_FragCoord.xy), 0).r;
    vec2 q = (gl_FragCoord.xy - 0.5 * _nv_screen) / _nv_screen.y;
    float m = 1.0e9;
    for (int i = 0; i < _nv_tubeCount; i++) {
        m = min(m, length(q - _nv_tubes[i].xy) / _nv_tubes[i].z);
    }
    if (y >= 0.0) {
        float rim = smoothstep(1.0, 0.95, m);
        _nv_colour = vec4(_nv_phosphor * pow(y, 0.85) * rim, _nv_fade);
    } else {
        _nv_colour = vec4(0.0, 0.0, 0.0, smoothstep(1.4, 1.0, m) * 0.9 * _nv_fade);
    }
}
