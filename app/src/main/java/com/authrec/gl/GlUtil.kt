package com.authrec.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLES31
import android.view.Surface

/**
 * EGL display with the main GLES 3.2 context (8-bit RGBA, for compute and the preview surface)
 * and, while recording, a second context sharing its textures whose config matches the encoder
 * surface (10-bit RGBA1010102 for 10-bit recording).
 */
internal class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val mainConfig: EGLConfig
    val mainContext: EGLContext

    init {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "eglInitialize failed" }
        mainConfig = chooseConfig(tenBit = false, recordable = false) ?: error("no GLES3 window config")
        mainContext = createContext(mainConfig, EGL14.EGL_NO_CONTEXT)
        makeCurrent(EGL14.EGL_NO_SURFACE, mainContext)
    }

    fun chooseConfig(tenBit: Boolean, recordable: Boolean): EGLConfig? {
        val bits = if (tenBit) intArrayOf(10, 10, 10, 2) else intArrayOf(8, 8, 8, 8)
        val attrs = mutableListOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, bits[0], EGL14.EGL_GREEN_SIZE, bits[1],
            EGL14.EGL_BLUE_SIZE, bits[2], EGL14.EGL_ALPHA_SIZE, bits[3],
        )
        if (recordable) attrs += listOf(EGL_RECORDABLE_ANDROID, 1)
        attrs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, count, 0)
        return if (count[0] > 0) configs[0] else null
    }

    fun createContext(config: EGLConfig, share: EGLContext): EGLContext {
        val ctx = EGL14.eglCreateContext(display, config, share, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${EGL14.eglGetError().toString(16)}" }
        return ctx
    }

    fun createWindowSurface(surface: Surface, config: EGLConfig = mainConfig): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}" }
        return s
    }

    /** EGL_NO_SURFACE relies on EGL_KHR_surfaceless_context, which every GLES3 Android device has. */
    fun makeCurrent(surface: EGLSurface, context: EGLContext = mainContext) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}" }
    }

    fun destroySurface(surface: EGLSurface) = EGL14.eglDestroySurface(display, surface)

    fun destroyContext(context: EGLContext) = EGL14.eglDestroyContext(display, context)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, mainContext)
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Small GL helpers. */
internal object Gl {

    fun texture2D(internalFormat: Int, w: Int, h: Int, linear: Boolean): Int {
        val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, w, h)
        // Integer and 32-bit float textures are incomplete with linear filtering.
        val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex
    }

    fun bindTexture(unit: Int, target: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, tex)
    }

    fun uniform(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)

    fun computeProgram(src: String): Int = link(compile(GLES31.GL_COMPUTE_SHADER, src))

    fun renderProgram(vertex: String, fragment: String): Int =
        link(compile(GLES30.GL_VERTEX_SHADER, vertex), compile(GLES30.GL_FRAGMENT_SHADER, fragment))

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1).also { GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, it, 0) }[0]
        check(ok != 0) { "shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun link(vararg shaders: Int): Int {
        val prog = GLES30.glCreateProgram()
        shaders.forEach { GLES30.glAttachShader(prog, it) }
        GLES30.glLinkProgram(prog)
        shaders.forEach { GLES30.glDeleteShader(it) }
        val ok = IntArray(1).also { GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, it, 0) }[0]
        check(ok != 0) { "program link failed: ${GLES30.glGetProgramInfoLog(prog)}" }
        return prog
    }

    fun check(where: String) {
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "GL error 0x${err.toString(16)} during $where" }
    }
}
