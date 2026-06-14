package com.vrplayer.app

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenGL ES 2.0 renderer that projects a video onto a sphere (180° or 360°).
 *
 * The video comes from an external SurfaceTexture that ExoPlayer writes to.
 * Camera rotation is driven by the device gyroscope + touch drag.
 */
class VrRenderer : GLSurfaceView.Renderer {

    // ── Surface for ExoPlayer ──
    private var surfaceTexture: SurfaceTexture? = null
    private var texId = IntArray(1)
    /** Caller calls this to get a Surface for ExoPlayer. */
    var onSurfaceReady: ((SurfaceTexture) -> Unit)? = null

    // ── Private lock for cross-thread safety (avoids public `this` lock) ──
    private val lock = Any()

    // ── Request render callback for RENDERMODE_WHEN_DIRTY ──
    var onRequestRender: (() -> Unit)? = null

    // ── Camera state ──
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val layoutTransform = FloatArray(16)  // temp for SBS/OU layout matrix
    private val combinedTexMatrix = FloatArray(16) // layout * surfaceTexture transform

    // ── Viewport (cached from onSurfaceChanged) ──
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var cachedAspect = 1f

    /** Sensor rotation matrix (raw from TYPE_ROTATION_VECTOR, remapped for landscape). */
    var sensorMatrix: FloatArray? = null
        set(value) {
            synchronized(lock) { field = value?.copyOf() }
        }

    // ── Drag / gyro hybrid (written on UI thread, read on GL thread) ──
    @Volatile var useGyro = false
    @Volatile var dragLon = 0f          // degrees
    @Volatile var dragLat = 0f          // degrees

    // ── Projection ──
    @Volatile var fov = 75f             // degrees
    @Volatile var isFisheye = false

    // ── 3D layout ──
    @Volatile var layout3d = "sbs"      // "mono", "sbs", "ou"

    // ── Horizontal correction angle (degrees, applied per-frame) ──
    @Volatile var hCorrection = 0f

    // ── Sphere geometry ──
    private var sphereVertexBuffer: FloatBuffer? = null
    private var sphereTexCoordBuffer: FloatBuffer? = null
    private var sphereIndexBuffer: ShortBuffer? = null
    private var sphereIndexCount = 0
    @Volatile private var segmentsH = 48
    @Volatile private var segmentsV = 32
    @Volatile private var projection360 = false
    @Volatile private var geoVersion = 0
    private var lastGeoVersion = -1

    // ── GL handles ──
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMVPMatrixLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0

    private val texMatrix = FloatArray(16)

    // ── Public API ──

    /** Release native resources. Must call from the GL thread (or after GL context is current). */
    fun release() {
        // Clear frame-available listener to prevent callback after release
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        onSurfaceReady = null
        if (texId[0] != 0) {
            GLES20.glDeleteTextures(1, texId, 0)
            texId[0] = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        // Release vertex/index data
        sphereVertexBuffer = null
        sphereTexCoordBuffer = null
        sphereIndexBuffer = null
        sphereIndexCount = 0
    }

    fun setProjection(is360: Boolean) {
        if (projection360 != is360) {
            projection360 = is360
            geoVersion++
        }
    }

    fun setQuality(hSeg: Int, vSeg: Int) {
        segmentsH = hSeg
        segmentsV = vSeg
        geoVersion++
    }

    // ── GLSurfaceView.Renderer ──

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glFrontFace(GLES20.GL_CW)

        // Release previous GL resources if this is a context recreation
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texId[0] != 0) {
            GLES20.glDeleteTextures(1, texId, 0)
            texId[0] = 0
        }
        surfaceTexture?.release()
        surfaceTexture = null

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")

        // Set texture unit once (never changes per frame)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(uTextureLoc, 0)

        // Create external OES texture for SurfaceTexture
        GLES20.glGenTextures(1, texId, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texId[0]).also { st ->
            // Must set default buffer size so the decoder knows the surface
            // can accept video frames up to this resolution. Without this,
            // many hardware decoders reject the surface with NO_EXCEEDS_CAPABILITIES.
            st.setDefaultBufferSize(7680, 4320)
        }
        surfaceTexture?.setOnFrameAvailableListener {
            frameAvailable = true
            onRequestRender?.invoke()
        }
        onSurfaceReady?.invoke(surfaceTexture!!)

        Matrix.setIdentityM(texMatrix, 0)
        buildSphere()
        lastGeoVersion = geoVersion
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        cachedAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, fov, cachedAspect, 0.1f, 1000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Update sphere geometry if needed (version-counter pattern avoids lost updates)
        if (lastGeoVersion != geoVersion) {
            buildSphere()
            lastGeoVersion = geoVersion
        }

        // Update projection matrix (recompute each frame to catch FOV changes)
        val effectiveFov = if (isFisheye) 140f else fov
        Matrix.perspectiveM(projectionMatrix, 0, effectiveFov, cachedAspect, 0.1f, 1000f)

        // Build view matrix from sensor or drag
        Matrix.setIdentityM(viewMatrix, 0)

        val sm = synchronized(lock) { sensorMatrix }
        if (useGyro && sm != null) {
            // Transpose = inverse for a rotation matrix.
            // The sensor gives the device's rotation in world space.
            // Transposing gives the view matrix: how world objects
            // appear from the device's perspective.
            Matrix.transposeM(viewMatrix, 0, sm, 0)
            // Apply horizontal correction as a Y-axis rotation
            if (hCorrection != 0f) {
                Matrix.rotateM(viewMatrix, 0, hCorrection, 0f, 1f, 0f)
            }
        } else {
            // Drag-based rotation (no gyro)
            Matrix.rotateM(viewMatrix, 0, dragLat, 1f, 0f, 0f)
            Matrix.rotateM(viewMatrix, 0, dragLon, 0f, 1f, 0f)
        }

        // MVP = projection * view
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Compute SBS/OU layout transform
        updateLayoutTransform()

        // Update SurfaceTexture with latest video frame
        try {
            synchronized(lock) {
                if (frameAvailable) {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(texMatrix)
                    frameAvailable = false
                }
            }
        } catch (e: Exception) {
            Log.w("VrRenderer", "Frame update failed", e)
        }

        // Compose: apply layout transform AFTER the SurfaceTexture's own transform.
        // This way the SBS/OU half-selection is applied on top of the video frame's
        // orientation/scale correction.
        Matrix.multiplyMM(combinedTexMatrix, 0, layoutTransform, 0, texMatrix, 0)

        // Render sphere
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, combinedTexMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0])

        val vb = sphereVertexBuffer ?: return
        val tb = sphereTexCoordBuffer ?: return
        val ib = sphereIndexBuffer ?: return

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vb)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, tb)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount,
            GLES20.GL_UNSIGNED_SHORT, ib)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    // ── Private helpers ──

    @Volatile
    private var frameAvailable = false

    /** Compute the SBS/OU/Mono layout transform into [layoutTransform].
     *  Applied AFTER the SurfaceTexture's own transform to select the correct eye half. */
    private fun updateLayoutTransform() {
        Matrix.setIdentityM(layoutTransform, 0)

        when (layout3d) {
            "sbs" -> {
                // Show left half (x ∈ [0,0.5]): scale x by 0.5
                Matrix.scaleM(layoutTransform, 0, 0.5f, 1f, 1f)
            }
            "ou" -> {
                // Show top half: the top half of the video frame is at y ∈ [0.5, 1.0].
                // Scale y by 0.5 and translate by +0.5 so u,v ∈ [0,1] maps to y ∈ [0.5, 1.0].
                Matrix.translateM(layoutTransform, 0, 0f, 0.5f, 0f)
                Matrix.scaleM(layoutTransform, 0, 1f, 0.5f, 1f)
            }
            // "mono" — identity, show full frame
        }
    }

    /** Build sphere vertex/texcoord/index buffers. */
    private fun buildSphere() {
        val phiStart = if (projection360) 0f else PI.toFloat()
        val phiLength = if (projection360) (2f * PI).toFloat() else PI.toFloat()
        val rows = segmentsV + 1
        val cols = segmentsH + 1

        val vertices = FloatArray(rows * cols * 3)
        val texCoords = FloatArray(rows * cols * 2)
        val indices = ShortArray(segmentsV * segmentsH * 6)

        var vidx = 0
        var tidx = 0

        for (r in 0..segmentsV) {
            val v = r.toFloat() / segmentsV
            val theta = v * PI.toFloat()

            for (c in 0..segmentsH) {
                val u = c.toFloat() / segmentsH
                val phi = phiStart + u * phiLength

                val x = sin(theta) * cos(phi)
                val y = cos(theta)
                val z = sin(theta) * sin(phi)

                vertices[vidx++] = x * 500f
                vertices[vidx++] = y * 500f
                vertices[vidx++] = z * 500f

                texCoords[tidx++] = u
                texCoords[tidx++] = v
            }
        }

        var iidx = 0
        for (r in 0 until segmentsV) {
            for (c in 0 until segmentsH) {
                val a = (r * cols + c).toShort()
                val b = (r * cols + c + 1).toShort()
                val d = ((r + 1) * cols + c).toShort()
                val e = ((r + 1) * cols + c + 1).toShort()

                indices[iidx++] = a
                indices[iidx++] = b
                indices[iidx++] = d

                indices[iidx++] = b
                indices[iidx++] = e
                indices[iidx++] = d
            }
        }

        sphereVertexBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(vertices).position(0) }

        sphereTexCoordBuffer = ByteBuffer
            .allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(texCoords).position(0) }

        sphereIndexBuffer = ByteBuffer
            .allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .also { it.put(indices).position(0) }

        sphereIndexCount = indices.size
    }

    // ── Shader helpers ──

    companion object {
        private val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying mediump vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying mediump vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        fun loadShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                Log.e("VrRenderer", "Shader compile error: $log")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        fun createProgram(vert: String, frag: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vert)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, frag)
            if (vs == 0 || fs == 0) {
                if (vs != 0) GLES20.glDeleteShader(vs)
                if (fs != 0) GLES20.glDeleteShader(fs)
                return 0
            }
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            // Detach and delete shaders after linking — they're no longer needed
            GLES20.glDetachShader(prog, vs)
            GLES20.glDetachShader(prog, fs)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val status = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(prog)
                Log.e("VrRenderer", "Program link error: $log")
                GLES20.glDeleteProgram(prog)
                return 0
            }
            return prog
        }
    }
}

/**
 * Reference to GLES11Ext constants (avoids depending on the extension class directly).
 */
internal object GLES11Ext {
    const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
}
