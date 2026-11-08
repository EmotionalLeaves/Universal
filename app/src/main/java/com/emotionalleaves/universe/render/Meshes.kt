package com.emotionalleaves.universe.render

import android.opengl.GLES30
import kotlin.math.cos
import kotlin.math.sin

/** An indexed triangle mesh in a VAO. */
class Mesh(val vao: Int, val vbo: Int, val ibo: Int, val indexCount: Int, val mode: Int = GLES30.GL_TRIANGLES) {
    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(mode, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        Gl.deleteVao(vao); Gl.deleteBuffer(vbo); Gl.deleteBuffer(ibo)
    }

    companion object {
        /**
         * Builds a mesh from interleaved float vertices and int indices.
         * [layout] lists the component count of each attribute in order (location 0, 1, ...).
         */
        fun build(vertices: FloatArray, indices: IntArray, layout: IntArray, mode: Int = GLES30.GL_TRIANGLES): Mesh {
            val vao = Gl.genVao()
            val vbo = Gl.genBuffer()
            val ibo = Gl.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            Gl.bufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, Gl.floatBuffer(vertices), GLES30.GL_STATIC_DRAW)
            val stride = layout.sum() * 4
            var offset = 0
            for ((loc, n) in layout.withIndex()) {
                GLES30.glEnableVertexAttribArray(loc)
                GLES30.glVertexAttribPointer(loc, n, GLES30.GL_FLOAT, false, stride, offset)
                offset += n * 4
            }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            Gl.bufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, Gl.intBuffer(indices), GLES30.GL_STATIC_DRAW)
            GLES30.glBindVertexArray(0)
            return Mesh(vao, vbo, ibo, indices.size, mode)
        }
    }
}

object Meshes {
    /**
     * Unit UV sphere. Local +Z is the pole; u = 0.5 points at local +X (longitude 0),
     * matching equirectangular planet maps whose left edge is longitude -180.
     */
    fun sphere(stacks: Int = 48, slices: Int = 96): Mesh {
        val verts = ArrayList<Float>()
        for (i in 0..stacks) {
            val v = i.toFloat() / stacks
            val lat = Math.PI / 2 - v * Math.PI
            for (j in 0..slices) {
                val u = j.toFloat() / slices
                val lon = (u - 0.5) * 2 * Math.PI
                val x = cos(lat) * cos(lon)
                val y = cos(lat) * sin(lon)
                val z = sin(lat)
                verts.add(x.toFloat()); verts.add(y.toFloat()); verts.add(z.toFloat())
                verts.add(u); verts.add(v)
            }
        }
        val idx = ArrayList<Int>()
        val row = slices + 1
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = i * row + j
            val b = a + row
            idx.add(a); idx.add(b); idx.add(a + 1)
            idx.add(a + 1); idx.add(b); idx.add(b + 1)
        }
        return Mesh.build(verts.toFloatArray(), idx.toIntArray(), intArrayOf(3, 2))
    }

    /** Quad with corners at (-1..1, -1..1), attribute 0 = vec2 corner. */
    fun quad(): Mesh = Mesh.build(
        floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f),
        intArrayOf(0, 1, 2, 0, 2, 3),
        intArrayOf(2),
    )

    /** Flat annulus in the XY plane; attribute 0 = (x, y, t) with t = 0 at inner edge, 1 at outer. */
    fun ring(inner: Float, outer: Float, segments: Int = 160): Mesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (i in 0..segments) {
            val a = 2 * Math.PI * i / segments
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            v.add(c * inner); v.add(s * inner); v.add(0f)
            v.add(c * outer); v.add(s * outer); v.add(1f)
        }
        for (i in 0 until segments) {
            val a = i * 2
            idx.add(a); idx.add(a + 1); idx.add(a + 2)
            idx.add(a + 2); idx.add(a + 1); idx.add(a + 3)
        }
        return Mesh.build(v.toFloatArray(), idx.toIntArray(), intArrayOf(3))
    }

    /** Wireframe cube of edge 2 centred on the origin (for the 1 m scale-test cube). */
    fun cube(): Mesh {
        val p = floatArrayOf(
            -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f,
            -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f,
        )
        val tris = intArrayOf(
            0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
            2, 3, 7, 2, 7, 6, 1, 2, 6, 1, 6, 5, 0, 4, 7, 0, 7, 3,
        )
        // Expand to per-face vertices with normals packed as UVs is unnecessary; reuse sphere shader with pos only.
        val v = FloatArray(8 * 5)
        for (i in 0 until 8) {
            v[i * 5] = p[i * 3]; v[i * 5 + 1] = p[i * 3 + 1]; v[i * 5 + 2] = p[i * 3 + 2]
            v[i * 5 + 3] = 0.5f; v[i * 5 + 4] = 0.5f
        }
        return Mesh.build(v, tris, intArrayOf(3, 2))
    }
}

/** A batch of points (stars, galaxies...) stored relative to an anchor position. */
class PointBatch(
    val anchor: com.emotionalleaves.universe.core.UPos,
    val unit: Double,
    val count: Int,
    private val vao: Int,
    private val vbo: Int,
) {
    /** How many points to draw (the order is random, so any prefix is an even sample). */
    var limit = count

    fun draw() {
        val n = limit.coerceIn(0, count)
        if (n == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        Gl.deleteVao(vao); Gl.deleteBuffer(vbo)
    }

    companion object {
        /**
         * Uploads points. [data] holds per point: x, y, z (in [unit] metres relative to [anchor]),
         * then r, g, b (0..1), then absolute magnitude. 7 floats per point.
         */
        fun upload(anchor: com.emotionalleaves.universe.core.UPos, unit: Double, data: FloatArray): PointBatch {
            val count = data.size / 7
            val vao = Gl.genVao()
            val vbo = Gl.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            Gl.bufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, Gl.floatBuffer(data), GLES30.GL_STATIC_DRAW)
            val stride = 7 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glVertexAttrib4f(1, 1f, 1f, 1f, 1f)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glBindVertexArray(0)
            return PointBatch(anchor, unit, count, vao, vbo)
        }
    }
}

/** A line strip or line list stored relative to an anchor (orbits, guides). */
class LineMesh(val count: Int, private val vao: Int, private val vbo: Int, val mode: Int) {
    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(mode, 0, count)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        Gl.deleteVao(vao); Gl.deleteBuffer(vbo)
    }

    companion object {
        fun upload(points: FloatArray, mode: Int = GLES30.GL_LINE_STRIP): LineMesh {
            val vao = Gl.genVao()
            val vbo = Gl.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            Gl.bufferData(GLES30.GL_ARRAY_BUFFER, points.size * 4, Gl.floatBuffer(points), GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glBindVertexArray(0)
            return LineMesh(points.size / 3, vao, vbo, mode)
        }
    }
}
