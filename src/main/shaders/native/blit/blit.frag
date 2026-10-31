#version 450

// Copy (ANAGLYPH off): unit 0 as is. Anaglyph composite (ANAGLYPH on, constant_id 3): eye 0 in unit 0, eye 1 in unit 1,
// result (eye1.r, eye0.g, eye0.b) exactly like MITE's per-eye colour masks.
layout(constant_id = 3) const bool ANAGLYPH = false;

layout(set = 1, binding = 0) uniform sampler2D uEye0;
layout(set = 2, binding = 0) uniform sampler2D uEye1;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = texture(uEye0, vUv);
    if (ANAGLYPH) {
        c.r = texture(uEye1, vUv).r;
    }
    outColor = vec4(c.rgb, 1.0);
}
