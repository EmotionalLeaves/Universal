package com.emotionalleaves.universe.render

/**
 * GLSL sources. Every program draws relative to the camera (floating origin) and uses a
 * logarithmic depth buffer so a 1 m rock and a galaxy 10 billion light years away sort correctly.
 */
object Shaders {
    private const val HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

    private const val LOG_DEPTH_VS = """
uniform float uLogF;
out float vLogZ;
vec4 logDepth(vec4 p) {
    vLogZ = 1.0 + max(p.w, 1e-6);
    p.z = (log2(vLogZ) * uLogF - 1.0) * p.w;
    return p;
}
"""
    private const val LOG_DEPTH_FS = """
uniform float uLogF;
in float vLogZ;
void writeDepth() { gl_FragDepth = log2(vLogZ) * uLogF * 0.5; }
"""

    /** Cheap hash noise for procedural surfaces in fragment shaders. */
    private const val NOISE = """
float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}
float vnoise(vec3 p) {
    vec3 i = floor(p); vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i), n100 = hash31(i + vec3(1,0,0));
    float n010 = hash31(i + vec3(0,1,0)), n110 = hash31(i + vec3(1,1,0));
    float n001 = hash31(i + vec3(0,0,1)), n101 = hash31(i + vec3(1,0,1));
    float n011 = hash31(i + vec3(0,1,1)), n111 = hash31(i + vec3(1,1,1));
    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}
float fbm(vec3 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 6; i++) { s += a * vnoise(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
    return s;
}
"""

    // ---------------------------------------------------------------- points (stars, galaxies, glow)

    val POINTS_VS = HEADER + LOG_DEPTH_VS + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in float aMag;
uniform mat4 uViewProj;
uniform vec3 uOffset;      // batch anchor minus camera, metres
uniform float uScale;      // metres per unit of aPos
uniform float uMagLimit;   // apparent magnitude that maps to brightness 1
uniform float uSizeScale;  // point size (px) for brightness 1
uniform float uMaxSize;
uniform float uMinSize;
uniform float uFadeNear;   // metres: fade out points closer than this (0 = off)
uniform float uFadeFar;    // metres: fade out points farther than this (0 = off)
uniform float uAlpha;
uniform float uHide;       // index of a point to hide (system star), -1 = none
out vec4 vColor;
void main() {
    vec3 p = aPos * uScale + uOffset;
    float d = max(length(p), 1.0);
    float dpc = d / 3.0856776e16;
    float m = aMag + 5.0 * 0.30103 * log2(max(dpc, 1e-12)) - 5.0;
    float e = clamp(-0.4 * (m - uMagLimit), -20.0, 12.0);
    float b = exp2(e * 3.3219281);
    float size = clamp(uSizeScale * pow(b, 0.35), uMinSize, uMaxSize);
    float a = clamp(b, 0.0, 1.0);
    float fade = 1.0;
    if (uFadeNear > 0.0) fade *= smoothstep(uFadeNear * 0.4, uFadeNear, d);
    if (uFadeFar > 0.0) fade *= 1.0 - smoothstep(uFadeFar * 0.5, uFadeFar, d);
    if (float(gl_VertexID) == uHide) fade = 0.0;
    vColor = vec4(aColor.rgb, a * fade * uAlpha);
    gl_PointSize = size;
    gl_Position = logDepth(uViewProj * vec4(p, 1.0));
    if (vColor.a < 0.004) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
"""

    val POINTS_FS = HEADER + """
in vec4 vColor;
out vec4 oColor;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(c, c);
    if (r2 > 1.0) discard;
    float core = exp(-r2 * 5.0) + 0.35 * exp(-r2 * 1.5) * (1.0 - r2);
    oColor = vec4(vColor.rgb * vColor.a * core, 1.0);
}
"""

    // ---------------------------------------------------------------- planets, moons, stars as spheres

    val SPHERE_VS = HEADER + LOG_DEPTH_VS + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec3 vNormal;
out vec2 vUv;
out vec3 vLocal;
out vec3 vWorld;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = normalize(mat3(uModel) * aPos);
    vUv = aUv;
    vLocal = aPos;
    gl_Position = logDepth(uViewProj * w);
}
"""

    val SPHERE_FS = HEADER + LOG_DEPTH_FS + NOISE + """
in vec3 vNormal;
in vec2 vUv;
in vec3 vLocal;
in vec3 vWorld;
uniform sampler2D uTex;
uniform sampler2D uNight;
uniform int uHasTex;
uniform int uHasNight;
uniform int uStyle;        // 0 rocky, 1 gas giant, 2 ice, 3 lava, 4 ocean, 5 star, 6 plain
uniform vec3 uColorA;
uniform vec3 uColorB;
uniform vec3 uColorC;
uniform float uSeed;
uniform vec3 uLightDir;    // direction from surface toward the light
uniform float uAmbient;
out vec4 oColor;

vec3 procedural(vec3 p) {
    vec3 q = p * 2.0 + vec3(uSeed, uSeed * 0.37, uSeed * 0.71);
    if (uStyle == 1) {
        float lat = p.z;
        float bands = sin(lat * (14.0 + fract(uSeed) * 10.0) + fbm(q * 1.5) * 3.0);
        float t = 0.5 + 0.5 * bands;
        vec3 c = mix(uColorA, uColorB, t);
        return mix(c, uColorC, smoothstep(0.55, 0.8, fbm(q * 3.0 + vec3(lat * 4.0))) * 0.5);
    }
    if (uStyle == 5) {
        float g = fbm(q * 6.0);
        return uColorA * (0.85 + 0.3 * g);
    }
    float h = fbm(q * 1.2);
    if (uStyle == 0) {
        vec3 c = mix(uColorA, uColorB, smoothstep(0.35, 0.65, h));
        return mix(c, uColorC, smoothstep(0.62, 0.75, fbm(q * 4.0)) * 0.6);
    }
    if (uStyle == 2) {
        vec3 c = mix(uColorA, uColorB, smoothstep(0.4, 0.6, h));
        return mix(c, vec3(0.95), smoothstep(0.6, 0.9, abs(p.z)));
    }
    if (uStyle == 3) {
        float cracks = smoothstep(0.48, 0.5, h) * (1.0 - smoothstep(0.5, 0.52, h));
        return mix(uColorA, uColorB, smoothstep(0.3, 0.7, fbm(q * 3.0))) + uColorC * cracks * 2.0;
    }
    if (uStyle == 4) {
        vec3 c = h > 0.56 ? mix(uColorB, uColorC, smoothstep(0.56, 0.75, h)) : uColorA * (0.7 + 0.5 * h);
        return mix(c, vec3(0.95), smoothstep(0.75, 0.95, abs(p.z)));
    }
    return uColorA;
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 albedo = uHasTex == 1 ? texture(uTex, vUv).rgb : procedural(normalize(vLocal));
    if (uStyle == 5) {
        // Stars glow: limb darkening, no lighting
        vec3 v = normalize(-vWorld);
        float mu = max(dot(n, v), 0.0);
        float limb = 0.4 + 0.6 * pow(mu, 0.5);
        oColor = vec4(albedo * limb * 1.4, 1.0);
        writeDepth();
        return;
    }
    float diff = max(dot(n, normalize(uLightDir)), 0.0);
    vec3 col = albedo * (uAmbient + (1.0 - uAmbient) * diff);
    if (uHasNight == 1) {
        float night = 1.0 - smoothstep(-0.05, 0.15, dot(n, normalize(uLightDir)));
        col += texture(uNight, vUv).rgb * night * 0.9;
    }
    oColor = vec4(col, 1.0);
    writeDepth();
}
"""

    // ---------------------------------------------------------------- atmosphere shell (additive)

    val ATMO_FS = HEADER + LOG_DEPTH_FS + """
in vec3 vNormal;
in vec2 vUv;
in vec3 vLocal;
in vec3 vWorld;
uniform vec3 uColorA;
uniform vec3 uLightDir;
uniform float uStrength;
out vec4 oColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(-vWorld);
    float rim = 1.0 - abs(dot(n, v));
    float lit = smoothstep(-0.3, 0.4, dot(n, normalize(uLightDir)));
    float a = pow(rim, 3.0) * lit * uStrength;
    oColor = vec4(uColorA * a, 1.0);
    writeDepth();
}
"""

    // ---------------------------------------------------------------- lines (orbits, markers)

    val LINE_VS = HEADER + LOG_DEPTH_VS + """
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform float uScale;
uniform mat3 uRot;
void main() {
    vec3 p = uRot * (aPos * uScale) + uOffset;
    gl_Position = logDepth(uViewProj * vec4(p, 1.0));
}
"""

    val LINE_FS = HEADER + LOG_DEPTH_FS + """
uniform vec4 uColor;
out vec4 oColor;
void main() {
    oColor = vec4(uColor.rgb * uColor.a, uColor.a);
    writeDepth();
}
"""

    // ---------------------------------------------------------------- billboards (glows, galaxies, nebulae)

    val BILLBOARD_VS = HEADER + LOG_DEPTH_VS + """
layout(location = 0) in vec2 aCorner;
uniform mat4 uViewProj;
uniform vec3 uCenter;   // relative to camera
uniform vec3 uAxisU;    // half-width axis, metres
uniform vec3 uAxisV;    // half-height axis, metres
out vec2 vCoord;
void main() {
    vCoord = aCorner;
    vec3 p = uCenter + uAxisU * aCorner.x + uAxisV * aCorner.y;
    gl_Position = logDepth(uViewProj * vec4(p, 1.0));
}
"""

    val BILLBOARD_FS = HEADER + LOG_DEPTH_FS + NOISE + """
in vec2 vCoord;
uniform int uStyle;     // 0 glow, 1 spiral galaxy, 2 elliptical galaxy, 3 nebula, 4 ring nebula, 5 irregular galaxy, 6 lens flare ring
uniform vec3 uColorA;
uniform vec3 uColorB;
uniform float uSeed;
uniform float uAlpha;
uniform float uArms;
uniform float uTwist;
out vec4 oColor;
void main() {
    float r = length(vCoord);
    if (r > 1.0) discard;
    vec3 c = vec3(0.0);
    if (uStyle == 0) {
        float g = exp(-r * r * 9.0) + 0.25 * exp(-r * 4.0) * (1.0 - r);
        c = uColorA * g;
    } else if (uStyle == 1) {
        float ang = atan(vCoord.y, vCoord.x);
        float lr = log(max(r, 0.02));
        float arms = 0.5 + 0.5 * cos(uArms * (ang - uTwist * lr) + uSeed);
        float n = fbm(vec3(vCoord * 6.0, uSeed));
        float disk = exp(-r * 4.0);
        float bulge = exp(-r * r * 60.0);
        float armLight = pow(arms, 3.0) * smoothstep(1.0, 0.15, r) * (0.6 + 0.6 * n);
        c = uColorA * bulge * 1.6 + uColorB * (disk * 0.45 + armLight * disk * 1.6);
    } else if (uStyle == 2) {
        float g = exp(-r * r * 7.0) + 0.3 * exp(-r * 3.5);
        c = uColorA * g * (1.0 - r);
    } else if (uStyle == 3) {
        float n = fbm(vec3(vCoord * 3.0, uSeed));
        float m = fbm(vec3(vCoord * 7.0 + 3.1, uSeed + 1.7));
        float shape = smoothstep(1.0, 0.2, r + (n - 0.5) * 0.8);
        c = mix(uColorA, uColorB, m) * shape * (0.4 + n) ;
    } else if (uStyle == 4) {
        float ring = exp(-pow((r - 0.6) * 6.0, 2.0));
        float n = fbm(vec3(vCoord * 5.0, uSeed));
        c = mix(uColorA, uColorB, r) * (ring * (0.6 + 0.6 * n) + 0.15 * exp(-r * 6.0));
    } else if (uStyle == 5) {
        float n = fbm(vec3(vCoord * 4.0, uSeed));
        float shape = smoothstep(1.0, 0.1, r + (n - 0.5) * 0.9);
        c = mix(uColorA, uColorB, n) * shape * 0.9;
    } else {
        c = uColorA * exp(-r * r * 3.0);
    }
    c *= (1.0 - smoothstep(0.85, 1.0, r));
    oColor = vec4(c * uAlpha, 1.0);
    writeDepth();
}
"""

    // ---------------------------------------------------------------- planetary rings

    val RING_VS = HEADER + LOG_DEPTH_VS + """
layout(location = 0) in vec3 aPos;   // x,y on the ring plane, z = normalized radius 0..1
uniform mat4 uModel;
uniform mat4 uViewProj;
out float vR;
out vec3 vWorld;
void main() {
    vR = aPos.z;
    vec4 w = uModel * vec4(aPos.xy, 0.0, 1.0);
    vWorld = w.xyz;
    gl_Position = logDepth(uViewProj * w);
}
"""

    val RING_FS = HEADER + LOG_DEPTH_FS + """
in float vR;
in vec3 vWorld;
uniform sampler2D uTex;
uniform int uHasTex;
uniform vec3 uColorA;
uniform vec3 uPlanetCenter;
uniform float uPlanetRadius;
uniform vec3 uLightDir;
out vec4 oColor;
void main() {
    vec4 t = uHasTex == 1 ? texture(uTex, vec2(vR, 0.5)) : vec4(uColorA, 0.6 * (0.5 + 0.5 * sin(vR * 90.0)));
    // Planet shadow on the rings
    vec3 toP = uPlanetCenter - vWorld;
    float along = dot(toP, uLightDir);
    float shadow = 1.0;
    if (along < 0.0) {
        vec3 closest = toP - uLightDir * along;
        shadow = smoothstep(uPlanetRadius * 0.95, uPlanetRadius * 1.05, length(closest));
    }
    vec3 c = t.rgb * (0.15 + 0.85 * shadow);
    oColor = vec4(c, t.a);
    writeDepth();
}
"""
}
