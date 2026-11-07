package com.emotionalleaves.universe.render

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/** Small helpers around OpenGL ES 3.0. */
object Gl {
    private const val TAG = "UniverseGL"

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    fun intBuffer(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(data); position(0)
        }

    fun shortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(data); position(0)
        }

    fun genBuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        return ids[0]
    }

    fun genVao(): Int {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        return ids[0]
    }

    fun deleteBuffer(id: Int) {
        if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
    }

    fun deleteVao(id: Int) {
        if (id != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(id), 0)
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log\n--- source ---\n${numbered(src)}")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            throw RuntimeException("Program link failed: $log")
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    fun checkError(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }

    private fun numbered(src: String) = src.lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n")
}

/** A linked shader program with cached uniform locations. */
class Program(vs: String, fs: String) {
    val id: Int = Gl.program(vs, fs)
    private val locations = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set1f(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun set1i(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun set2f(name: String, a: Float, b: Float) = GLES30.glUniform2f(loc(name), a, b)
    fun set3f(name: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(loc(name), a, b, c)
    fun set4f(name: String, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(loc(name), a, b, c, d)
    fun setMat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)
    fun setMat3(name: String, m: FloatArray) = GLES30.glUniformMatrix3fv(loc(name), 1, false, m, 0)
}
