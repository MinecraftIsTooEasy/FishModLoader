#version 450

// Full-screen triangle without vertex input. Source row 0 is the top of the picture (same convention as every render target).
layout(location = 0) out vec2 vUv;

void main() {
    vec2 t = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vUv = vec2(t.x, 1.0 - t.y);
    gl_Position = vec4(t * 2.0 - 1.0, 0.0, 1.0);
}
