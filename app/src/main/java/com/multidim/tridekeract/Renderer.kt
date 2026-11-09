package com.multidim.tridekeract

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colors for the current light/dark theme, matching the web page. */
data class Palette(val bg: FloatArray, val fg: FloatArray, val edgeS: Float, val edgeL: Float)

enum class ViewMode { MONO, CROSS, PARALLEL }

/**
 * Draws every edge of every open dimension as an instanced quad, exactly like the page's
 * WebGL2 renderer: corner positions go into a float texture, each edge instance names its two
 * corners and its axis, and the vertex shader rotates, applies the stereo eye and perspective.
 */
class Renderer(private val viz: Viz, private val density: Float) : GLSurfaceView.Renderer {
    @Volatile var palette = Palette(floatArrayOf(0.965f, 0.953f, 0.961f), floatArrayOf(0.133f, 0.086f, 0.118f), 72f, 44f)
    @Volatile var mode = ViewMode.CROSS
    /** Called once per frame on the drawing thread before anything moves. */
    var onFrame: ((Double) -> Unit)? = null

    private var prog = 0
    private var vao = 0
    private var instBuf = 0
    private var tex = 0
    private var count = 0
    private var key = ""
    private var wPx = 1; private var hPx = 1
    private val posBuf: FloatBuffer = ByteBuffer.allocateDirect(256 * 128 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val posArr = FloatArray(256 * 128 * 4)
    private val widths = FloatArray(16)
    private val colors = FloatArray(64)
    private val rot = FloatArray(9)
    private var uPos = 0; private var uR = 0; private var uEye = 0; private var uCenter = 0; private var uK = 0
    private var uRes = 0; private var uWidths = 0; private var uColors = 0
    private var last = 0L

    private val vs = """#version 300 es
precision highp float;precision highp int;
layout(location=0) in vec2 corner;
layout(location=1) in uvec3 inst;
uniform highp sampler2D pos;
uniform mat3 R;uniform vec2 eye;uniform vec2 center;uniform float k;uniform vec2 res;
uniform float widths[16];uniform vec4 colors[16];
flat out vec4 vcol;
vec3 fetchP(uint i){return texelFetch(pos,ivec2(int(i%256u),int(i/256u)),0).xyz;}
vec2 proj(vec3 p){vec3 q=R*p;float x3=q.x*eye.x-q.z*eye.y;float z3=q.x*eye.y+q.z*eye.x;float f=4.0/(4.0-z3);return vec2(center.x+x3*k*f,center.y-q.y*k*f);}
void main(){
  vec2 A=proj(fetchP(inst.x)),B=proj(fetchP(inst.y));vec2 d=B-A;float L=length(d);
  vec2 n=L>1e-4?vec2(-d.y,d.x)/L:vec2(0.0,1.0);
  float w=widths[inst.z];
  vec2 P=mix(A,B,corner.x)+n*corner.y*w*0.5;
  gl_Position=vec4(P.x/res.x*2.0-1.0,1.0-P.y/res.y*2.0,0.0,1.0);
  vcol=colors[inst.z];
}"""
    private val fs = """#version 300 es
precision mediump float;flat in vec4 vcol;out vec4 o;void main(){o=vec4(vcol.rgb*vcol.a,vcol.a);}"""

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES30.glGetShaderInfoLog(s))
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(prog, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(prog)
        uPos = GLES30.glGetUniformLocation(prog, "pos")
        uR = GLES30.glGetUniformLocation(prog, "R")
        uEye = GLES30.glGetUniformLocation(prog, "eye")
        uCenter = GLES30.glGetUniformLocation(prog, "center")
        uK = GLES30.glGetUniformLocation(prog, "k")
        uRes = GLES30.glGetUniformLocation(prog, "res")
        uWidths = GLES30.glGetUniformLocation(prog, "widths[0]")
        uColors = GLES30.glGetUniformLocation(prog, "colors[0]")

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        val quad = floatArrayOf(0f, -1f, 0f, 1f, 1f, -1f, 1f, 1f)
        val qb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        qb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glGenBuffers(1, ids, 0); instBuf = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instBuf)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribIPointer(1, 3, GLES30.GL_UNSIGNED_INT, 0, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glBindVertexArray(0)

        GLES30.glGenTextures(1, ids, 0); tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 256, 128, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)
        key = ""
        last = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        wPx = width; hPx = height
        GLES30.glViewport(0, 0, width, height)
    }

    private fun rebuild(axes: IntArray, m: Int) {
        val n = 1 shl m
        var total = 0
        for (bi in 0 until m) total += n / 2
        val list = IntArray(total * 3)
        var c = 0
        for (bi in (3 until m) + (0 until 3)) {
            val bit = 1 shl bi
            for (v in 0 until n) if (v and bit == 0) { list[c++] = v; list[c++] = v or bit; list[c++] = axes[bi] }
        }
        val ib: IntBuffer = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(list)
        ib.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * 4, ib, GLES30.GL_DYNAMIC_DRAW)
        count = total
    }

    private fun hue(a: Int, n: Int): Float = if (n <= 1) 300f else ((215 - a * (215 - (-25)) / (n - 1.0) + 360).let { Math.round(it).toInt() } % 360).toFloat()

    private fun hsl(h: Float, s: Float, l: Float, out: FloatArray, at: Int) {
        val ss = s / 100f; val ll = l / 100f
        val a = ss * min(ll, 1 - ll)
        fun f(n: Int): Float { val k = (n + h / 30f) % 12f; return ll - a * max(-1f, min(min(k - 3f, 9f - k), 1f)) }
        out[at] = f(0); out[at + 1] = f(8); out[at + 2] = f(4)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = min(0.05, (now - last) / 1e9)
        last = now
        val pal = palette
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(pal.bg[0], pal.bg[1], pal.bg[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val W = wPx / density; val H = hPx / density
        var m = 3
        var n = 8
        synchronized(viz) {
            onFrame?.invoke(dt)
            m = viz.m; n = 1 shl m
            val k = viz.axes.copyOf(m).joinToString(",")
            if (k != key) { key = k; rebuild(viz.axes, m) }
            val f0 = if (viz.fit != 0.0) viz.fit.toFloat() else 1f
            for (v in 0 until n) {
                posArr[v * 4] = viz.q[v * 3] / f0; posArr[v * 4 + 1] = viz.q[v * 3 + 1] / f0; posArr[v * 4 + 2] = viz.q[v * 3 + 2] / f0
            }
            viz.rotation(rot)
            val half = mode != ViewMode.MONO
            val sc = (if (half) min(W / 2, H) else min(W, H)) * 0.42f
            val thin = 0.6f * max(1f, min(2f, sc / 150f))
            val copies = 64.0 / (1 shl (m - 3))
            val va = (1 - 0.93.pow(copies)).toFloat()
            for (a in 0 until 3) { widths[a] = thin * 1.4f; colors[a * 4] = pal.fg[0]; colors[a * 4 + 1] = pal.fg[1]; colors[a * 4 + 2] = pal.fg[2]; colors[a * 4 + 3] = va }
            val share = min(1.0, 256.0 / (1 shl (m - 1)))
            for (a in 3 until Viz.DIM) {
                val o = viz.hOpen[a]
                hsl(hue(viz.domBand[a], Viz.HB), pal.edgeS, pal.edgeL, colors, a * 4)
                val a0 = 0.2 + o * 0.55
                widths[a] = if (o < 0.01) 0f else (thin * (1 + o * 0.6)).toFloat()
                colors[a * 4 + 3] = if (o < 0.01) 0f else (1 - (1 - a0).pow(share)).toFloat()
            }
            uZoom = viz.zoom.toFloat()
        }
        posBuf.position(0); posBuf.put(posArr, 0, n * 4); posBuf.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 256, (n + 255) / 256, GLES30.GL_RGBA, GLES30.GL_FLOAT, posBuf)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(prog)
        GLES30.glBindVertexArray(vao)
        GLES30.glUniform1i(uPos, 0)
        GLES30.glUniformMatrix3fv(uR, 1, false, rot, 0)
        GLES30.glUniform2f(uRes, W, H)
        GLES30.glUniform1fv(uWidths, 16, widths, 0)
        GLES30.glUniform4fv(uColors, 16, colors, 0)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val phi = (3.5 * Math.PI / 180).toFloat()
        when (mode) {
            ViewMode.MONO -> view(0f, W, W / 2, H / 2, min(W, H) * 0.42f, 0f)
            else -> {
                val hw = W / 2; val sc = min(hw, H) * 0.42f
                val aL = if (mode == ViewMode.CROSS) phi else -phi
                view(0f, hw, hw / 2, H / 2, sc, aL)
                view(hw, hw, hw * 1.5f, H / 2, sc, -aL)
            }
        }
        GLES30.glBindVertexArray(0)
    }

    private var uZoom = 1f

    private fun view(x0: Float, w: Float, cx: Float, cy: Float, sc: Float, eye: Float) {
        GLES30.glScissor(Math.round(x0 * density), 0, Math.round(w * density), hPx)
        GLES30.glUniform2f(uEye, kotlin.math.cos(eye), kotlin.math.sin(eye))
        GLES30.glUniform2f(uCenter, cx, cy)
        GLES30.glUniform1f(uK, sc * 0.86f * uZoom)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
    }
}

/** Ask for 4× multisampling, falling back to a plain RGBA8888 surface. */
class MsaaChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val ES3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
        val withMsaa = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, ES3, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE)
        val plain = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, ES3, EGL10.EGL_NONE)
        for (attrs in arrayOf(withMsaa, plain)) {
            val num = IntArray(1)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attrs, cfgs, 1, num) && num[0] > 0 && cfgs[0] != null) return cfgs[0]!!
        }
        throw IllegalStateException("No OpenGL ES 3 surface")
    }
}

