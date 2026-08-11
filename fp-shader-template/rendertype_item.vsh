// FIRST-PERSON WEAPON CORE-SHADER TEMPLATE (proof of concept).
//
// This is the CLASSIC (1.17-style) core-shader format. Your 26.2 client's item shader may differ -
// copy YOUR client's assets/minecraft/shaders/core/rendertype_item.vsh as the base and paste the two
// marked blocks into it. Then put the file in a pack at the same path and build the pack.
//
// The plugin (FpShader, fp-shader.enabled: true) writes an animation phase into the held gun's
// dyed_color, which arrives here as `Color` (vertexColor): RED = recoil (1->0 per shot), BLUE = aim.
// We displace only the FIRST-PERSON geometry, driven by that phase.

#version 150
#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    vec3 pos = Position;

    // ---- BEGIN weapon-animation displacement (paste into your client's shader) ----
    float recoil = Color.r;   // 1.0 right after a shot, decays to 0
    float aim    = Color.b;   // 1.0 while aiming
    // recoil: kick the model back (-Z) and up a touch; aim: raise + pull toward centre.
    pos.z -= recoil * 0.12;
    pos.y += recoil * 0.03;
    pos.y += aim * 0.02;
    pos.x -= aim * 0.03;
    // ---- END weapon-animation displacement ----

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(ModelViewMat, pos, FogShape);
    vertexColor = minecraft_mix_light(vec3(0.0), vec3(0.0), 1.0) * vec4(1.0);   // keep your client's lighting line
    texCoord0 = UV0;
}
