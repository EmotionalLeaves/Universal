package com.emotionalleaves.universe.render

import android.opengl.GLES30

/**
 * Bloom and a gentle finish: the scene is drawn into an offscreen image, its brightest parts are
 * blurred at quarter and eighth size and added back as a soft glow around stars, suns, galaxy cores
 * and city lights, then a light vignette and tone curve calm the edges and highlights.
 * Off on Low quality (and if the phone cannot make the offscreen images).
 */
class PostFx {
    private class Target(val w: Int, val h: Int, depth: Boolean) {
        val fbo: Int
        val tex: Int
        private var rbo = 0

        init {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0); tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            GLES30.glBindFramebuffer(FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(FRAMEBUFFER, COLOR0, GLES30.GL_TEXTURE_2D, tex, 0)
            if (depth) {
                GLES30.glGenRenderbuffers(1, ids, 0); rbo = ids[0]
                GLES30.glBindRenderbuffer(RENDERBUFFER, rbo)
                GLES30.glRenderbufferStorage(RENDERBUFFER, DEPTH24_STENCIL8, w, h)
                GLES30.glFramebufferRenderbuffer(FRAMEBUFFER, DEPTH_STENCIL, RENDERBUFFER, rbo)
            }
        }

        val complete get() = GLES30.glCheckFramebufferStatus(FRAMEBUFFER) == COMPLETE

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            if (rbo != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(rbo), 0)
        }
    }

    private var scene: Target? = null
    private var a: Target? = null
    private var b: Target? = null
    private var c: Target? = null
    private var d: Target? = null
    private var screenFbo = 0
    private var w = 0
    private var h = 0
    private var failed = false
    private val bright = Program(VS, BRIGHT_FS)
    private val blur = Program(VS, BLUR_FS)
    private val down = Program(VS, DOWN_FS)
    private val compose = Program(VS, COMPOSE_FS)

    fun resize(width: Int, height: Int) {
        if (width == w && height == h) return
        release()
        w = width; h = height
        failed = false
    }

    private fun ensure(): Boolean {
        if (failed) return false
        if (scene != null) return true
        return try {
            val s = Target(w, h, true)
            if (!s.complete) { s.release(); failed = true; return false }
            scene = s
            a = Target((w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), false)
            b = Target((w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), false)
            c = Target((w / 8).coerceAtLeast(1), (h / 8).coerceAtLeast(1), false)
            d = Target((w / 8).coerceAtLeast(1), (h / 8).coerceAtLeast(1), false)
            true
        } catch (e: Throwable) {
            failed = true
            false
        } finally {
            GLES30.glBindFramebuffer(FRAMEBUFFER, screenFbo)
        }
    }

    /** Starts drawing the scene offscreen; false = draw straight to the screen as before. */
    fun begin(enabled: Boolean): Boolean {
        val cur = IntArray(1)
        GLES30.glGetIntegerv(FRAMEBUFFER_BINDING, cur, 0)
        screenFbo = cur[0]
        if (!enabled || !ensure()) return false
        GLES30.glBindFramebuffer(FRAMEBUFFER, scene!!.fbo)
        GLES30.glViewport(0, 0, w, h)
        return true
    }

    /** Blurs the bright parts and draws the finished picture to the screen. */
    fun end(strength: Float) {
        val s = scene ?: return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)
        pass(bright, s.tex, a!!) { it.set2f("uTexel", 1f / s.w, 1f / s.h) }
        pass(blur, a!!.tex, b!!) { it.set2f("uDir", 1f / a!!.w, 0f) }
        pass(blur, b!!.tex, a!!) { it.set2f("uDir", 0f, 1f / a!!.h) }
        pass(down, a!!.tex, c!!) { it.set2f("uTexel", 1f / a!!.w, 1f / a!!.h) }
        pass(blur, c!!.tex, d!!) { it.set2f("uDir", 1.5f / c!!.w, 0f) }
        pass(blur, d!!.tex, c!!) { it.set2f("uDir", 0f, 1.5f / c!!.h) }
        GLES30.glBindFramebuffer(FRAMEBUFFER, screenFbo)
        GLES30.glViewport(0, 0, w, h)
        compose.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.tex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a!!.tex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, c!!.tex)
        compose.set1i("uScene", 0); compose.set1i("uBloomA", 1); compose.set1i("uBloomB", 2)
        compose.set1f("uStrength", strength)
        quad.draw()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glDepthMask(true)
    }

    private val quad by lazy { Meshes.quad() }

    private fun pass(p: Program, src: Int, dst: Target, setup: (Program) -> Unit) {
        GLES30.glBindFramebuffer(FRAMEBUFFER, dst.fbo)
        GLES30.glViewport(0, 0, dst.w, dst.h)
        p.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
        p.set1i("uTex", 0)
        setup(p)
        quad.draw()
    }

    fun release() {
        for (t in listOf(scene, a, b, c, d)) t?.release()
        scene = null; a = null; b = null; c = null; d = null
    }

    private companion object {
        const val FRAMEBUFFER = 0x8D40
        const val RENDERBUFFER = 0x8D41
        const val COLOR0 = 0x8CE0
        const val DEPTH_STENCIL = 0x821A
        const val DEPTH24_STENCIL8 = 0x88F0
        const val COMPLETE = 0x8CD5
        const val FRAMEBUFFER_BINDING = 0x8CA6

        const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        // Quarter-size copy of only what is bright (soft knee so it fades in smoothly).
        const val BRIGHT_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 o;
vec3 pick(vec2 uv) {
    vec3 c = texture(uTex, uv).rgb;
    float l = max(c.r, max(c.g, c.b));
    float k = smoothstep(0.82, 1.0, l);
    return c * k;
}
void main() {
    vec2 d = uTexel;
    vec3 s = pick(vUv + vec2(-d.x, -d.y)) + pick(vUv + vec2(d.x, -d.y)) + pick(vUv + vec2(-d.x, d.y)) + pick(vUv + vec2(d.x, d.y));
    o = vec4(s * 0.25, 1.0);
}
"""
        const val DOWN_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 o;
void main() {
    vec2 d = uTexel;
    vec3 s = texture(uTex, vUv + vec2(-d.x, -d.y)).rgb + texture(uTex, vUv + vec2(d.x, -d.y)).rgb
           + texture(uTex, vUv + vec2(-d.x, d.y)).rgb + texture(uTex, vUv + vec2(d.x, d.y)).rgb;
    o = vec4(s * 0.25, 1.0);
}
"""
        // 9-tap Gaussian using linear filtering (5 fetches).
        const val BLUR_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uDir;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 s = texture(uTex, vUv).rgb * 0.227027;
    s += texture(uTex, vUv + uDir * 1.384615).rgb * 0.316216;
    s += texture(uTex, vUv - uDir * 1.384615).rgb * 0.316216;
    s += texture(uTex, vUv + uDir * 3.230769).rgb * 0.070270;
    s += texture(uTex, vUv - uDir * 3.230769).rgb * 0.070270;
    o = vec4(s, 1.0);
}
"""
        const val COMPOSE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uScene;
uniform sampler2D uBloomA;
uniform sampler2D uBloomB;
uniform float uStrength;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 c = texture(uScene, vUv).rgb;
    vec3 glow = texture(uBloomA, vUv).rgb * 0.9 + texture(uBloomB, vUv).rgb * 1.1;
    c += glow * uStrength;
    // Soft shoulder instead of hard clipping, so bright cores do not burn to flat white.
    c = c / (1.0 + max(c - 0.8, 0.0) * 1.2);
    // Very light vignette: draws the eye in and calms the corners.
    vec2 q = vUv - 0.5;
    c *= 1.0 - dot(q, q) * 0.35;
    o = vec4(c, 1.0);
}
"""
    }
}
