package com.emotionalleaves.universe.render

import android.opengl.GLES30
import com.emotionalleaves.universe.core.Mat4
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Vec3d
import kotlin.math.tan

/** How a sphere is shaded. Colours are 0..1 RGB triples. */
class Material(
    val style: Int,
    val colorA: FloatArray = floatArrayOf(0.6f, 0.6f, 0.6f),
    val colorB: FloatArray = floatArrayOf(0.4f, 0.4f, 0.4f),
    val colorC: FloatArray = floatArrayOf(0.8f, 0.8f, 0.8f),
    val seed: Float = 0f,
    val texture: Int = 0,
    val night: Int = 0,
    val ambient: Float = 0.04f,
) {
    companion object {
        const val ROCKY = 0
        const val GAS = 1
        const val ICE = 2
        const val LAVA = 3
        const val OCEAN = 4
        const val STAR = 5
        const val PLAIN = 6
        const val SKY = 7

        fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    }
}

/** Shared drawing routines for spheres, glows, atmospheres and lines. */
class BodyDrawer(private val programs: Programs) {

    fun beginOpaque() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
    }

    fun beginAdditive() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    fun beginAlpha() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    /** Flat ring in the body's equator plane (Saturn). */
    fun ring(
        frame: Frame, mesh: Mesh, rel: Vec3d, rotation: Quatd, outer: Double, texture: Int, color: FloatArray,
        planetRadius: Double, lightDir: Vec3d,
    ) {
        val p = programs.ring
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        p.setMat4("uModel", Mat4.model(rotation, outer, rel))
        if (texture != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            p.set1i("uTex", 0)
            p.set1i("uHasTex", 1)
        } else p.set1i("uHasTex", 0)
        p.set3f("uColorA", color[0], color[1], color[2])
        p.set3f("uPlanetCenter", rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
        p.set1f("uPlanetRadius", planetRadius.toFloat())
        val l = lightDir.normalized()
        p.set3f("uLightDir", l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
        mesh.draw()
    }

    fun end() {
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    /** Draws a sphere of [radius] centred at camera-relative [rel]. [lightDir] points toward the light. */
    fun sphere(
        frame: Frame, rel: Vec3d, radius: Double, rotation: Quatd, m: Material, lightDir: Vec3d,
        mesh: Mesh = programs.sphereMesh,
        alphaMode: Int = 0,
        opacity: Float = 1f,
    ) {
        val p = programs.sphere
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        p.setMat4("uModel", Mat4.model(rotation, radius, rel))
        if (m.texture != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.texture)
            p.set1i("uTex", 0)
            p.set1i("uHasTex", 1)
        } else {
            p.set1i("uHasTex", 0)
        }
        if (m.night != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.night)
            p.set1i("uNight", 1)
            p.set1i("uHasNight", 1)
        } else {
            p.set1i("uHasNight", 0)
        }
        p.set1i("uStyle", m.style)
        p.set3f("uColorA", m.colorA[0], m.colorA[1], m.colorA[2])
        p.set3f("uColorB", m.colorB[0], m.colorB[1], m.colorB[2])
        p.set3f("uColorC", m.colorC[0], m.colorC[1], m.colorC[2])
        p.set1f("uSeed", m.seed)
        val l = lightDir.normalized()
        p.set3f("uLightDir", l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
        p.set1f("uAmbient", m.ambient)
        p.set1i("uAlphaMode", alphaMode)
        p.set1f("uOpacity", opacity)
        mesh.draw()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Additive rim glow just outside a planet. */
    fun atmosphere(frame: Frame, rel: Vec3d, radius: Double, color: FloatArray, lightDir: Vec3d, strength: Float) {
        val p = programs.atmosphere
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        p.setMat4("uModel", Mat4.model(Quatd.IDENTITY, radius, rel))
        p.set3f("uColorA", color[0], color[1], color[2])
        val l = lightDir.normalized()
        p.set3f("uLightDir", l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
        p.set1f("uStrength", strength)
        programs.lowSphereMesh.draw()
    }

    /** Camera-facing glow of [radius] metres (or at least [minPixels] on screen). */
    fun glow(frame: Frame, rel: Vec3d, radius: Double, color: FloatArray, alpha: Float, minPixels: Double = 0.0, style: Int = 0) {
        val dist = rel.length()
        val pxPerMetre = frame.height / 2.0 / (dist * tan(frame.fovY / 2))
        val r = maxOf(radius, minPixels / pxPerMetre)
        val right = frame.orientation.rotate(Vec3d.X) * r
        val up = frame.orientation.rotate(Vec3d.Y) * r
        billboard(frame, rel, right, up, style, color, color, 0f, alpha)
    }

    /** General billboard with explicit axes (for flat galaxies, nebulae...). */
    fun billboard(
        frame: Frame, rel: Vec3d, axisU: Vec3d, axisV: Vec3d, style: Int,
        colorA: FloatArray, colorB: FloatArray, seed: Float, alpha: Float, arms: Float = 2f, twist: Float = 3f,
    ) {
        val p = programs.billboard
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        p.set3f("uCenter", rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
        p.set3f("uAxisU", axisU.x.toFloat(), axisU.y.toFloat(), axisU.z.toFloat())
        p.set3f("uAxisV", axisV.x.toFloat(), axisV.y.toFloat(), axisV.z.toFloat())
        p.set1i("uStyle", style)
        p.set3f("uColorA", colorA[0], colorA[1], colorA[2])
        p.set3f("uColorB", colorB[0], colorB[1], colorB[2])
        p.set1f("uSeed", seed)
        p.set1f("uAlpha", alpha)
        p.set1f("uArms", arms)
        p.set1f("uTwist", twist)
        programs.quad.draw()
    }

    /** Draws a line mesh whose vertices are in [scale] metres, rotated by [rot], anchored at [rel]. */
    fun lines(frame: Frame, mesh: LineMesh, rel: Vec3d, scale: Double, rot: Quatd, color: FloatArray, alpha: Float) {
        val p = programs.line
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        p.set3f("uOffset", rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
        p.set1f("uScale", scale.toFloat())
        p.setMat3("uRot", Mat4.rotation3(rot))
        p.set4f("uColor", color[0], color[1], color[2], alpha)
        mesh.draw()
    }

    /** Points batch with brightness from absolute magnitude and distance. */
    fun points(
        frame: Frame, batch: PointBatch, magLimit: Float, sizeScale: Float, alpha: Float,
        fadeNear: Double = 0.0, fadeFar: Double = 0.0, minSize: Float = 1.2f, maxSize: Float = 14f, hide: Int = -1,
        worldSize: Double = 0.0, glowGain: Float = 1f,
    ) {
        val p = programs.points
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        val off = frame.rel(batch.anchor)
        p.set3f("uOffset", off.x.toFloat(), off.y.toFloat(), off.z.toFloat())
        p.set1f("uScale", batch.unit.toFloat())
        p.set1f("uMagLimit", magLimit)
        p.set1f("uSizeScale", sizeScale)
        p.set1f("uMinSize", minSize)
        p.set1f("uMaxSize", maxSize)
        p.set1f("uFadeNear", fadeNear.toFloat())
        p.set1f("uFadeFar", fadeFar.toFloat())
        p.set1f("uAlpha", alpha)
        p.set1f("uHide", hide.toFloat())
        p.set1f("uWorldSize", worldSize.toFloat())
        p.set1f("uPxPerRad", (frame.height / frame.fovY).toFloat())
        p.set1f("uGlowGain", glowGain)
        batch.draw()
    }
}
