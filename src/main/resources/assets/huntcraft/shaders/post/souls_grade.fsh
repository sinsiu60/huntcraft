#version 330

// Souls-like color grade (Elden Ring feel): desaturate, cool grey shadows, dim golden highlights, lifted blacks, vignette.
// Values: assets/huntcraft/post_effect/souls_grade.json (SoulsGrade) - adjustable with a resource pack.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// All vec4 to keep std140 layout unambiguous. Tone = saturation, contrast, brightness, black lift. Frame.x = vignette.
layout(std140) uniform SoulsGrade {
    vec4 ShadowTint;
    vec4 HighlightTint;
    vec4 Tone;
    vec4 Frame;
};

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;

    // desaturate
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, Tone.x);

    // contrast around mid grey, brightness
    c = (c - 0.5) * Tone.y + 0.5;
    c *= Tone.z;

    // shadow / highlight tint by luminance
    float t = smoothstep(0.15, 0.85, dot(c, LUMA));
    c *= mix(ShadowTint.rgb, HighlightTint.rgb, t);

    // lift blacks (murky darkness instead of pure black)
    c = Tone.w + c * (1.0 - Tone.w);

    // vignette
    vec2 d = texCoord - 0.5;
    float v = 1.0 - Frame.x * smoothstep(0.25, 0.75, dot(d, d) * 2.0);
    c *= v;

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
