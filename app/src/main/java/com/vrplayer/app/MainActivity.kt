package com.vrplayer.app

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Native VR Player for Android.
 *
 * Replaces the old WebView+Three.js approach with a native OpenGL ES 2.0
 * renderer + ExoPlayer for hardware-accelerated video decoding.
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    // ── Views ──
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var rootLayout: FrameLayout

    // Start screen
    private lateinit var startScreen: View
    private lateinit var btnSelectVideo: Button

    // Player
    private lateinit var playerView: View
    private lateinit var btnClose: ImageButton
    private lateinit var guiBar: View
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSkipBack: ImageButton
    private lateinit var btnSkipFwd: ImageButton
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var scrubBar: SeekBar
    private lateinit var btnVolume: ImageButton
    private lateinit var volumeSlider: SeekBar
    private lateinit var btnSettings: ImageButton
    private lateinit var btnFullscreen: ImageButton

    // Toast
    private lateinit var toastText: TextView
    private lateinit var spinner: View
    private lateinit var errorOverlay: View
    private lateinit var errorText: TextView
    private lateinit var btnRetry: Button

    // Settings
    private lateinit var settingsBackdrop: View
    private lateinit var settingsPanel: View
    private lateinit var btnSettingsClose: ImageButton
    private lateinit var settingsBody: LinearLayout

    // ── ExoPlayer ──
    private var player: ExoPlayer? = null
    private var currentVideoUri: Uri? = null
    private var videoSurface: Surface? = null

    // ── Audio focus ──
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // ── Sensors ──
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(16)
    private var hasGyro = false

    // ── Touch state ──
    private var isDragging = false
    private var dragStarted = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPinching = false
    private var pinchDist0 = 0f
    private var pinchFov0 = 75f
    private var guiVisible = true
    private var guiTimer: Runnable? = null
    private val GUI_TIMEOUT_MS = 3000L

    // ── Handler for main thread scheduling ──
    private val handler = Handler(Looper.getMainLooper())
    private var timeUpdater: Runnable? = null

    // ── Settings ──
    private val prefs by lazy { getSharedPreferences("vrplayer", MODE_PRIVATE) }
    private var settings: VrSettings = VrSettings()
    private var settingsPanelBuilt = false

    // ── Gesture detector for tap ──
    private lateinit var gestureDetector: GestureDetector

    // ── File picker ──
    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadVideo(uri)
            }
        }
    }

    // ── Lifecycle ──

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.windowInsetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.systemBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }

        loadSettings()
        setContentView(R.layout.activity_main)
        initViews()
        setupGL()
        setupSensors()
        setupFilePicker()
        setupTouch()
        setupAudioFocus()
        requestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        player?.let { if (it.playWhenReady) it.play() }
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Restart time updater if playing
        player?.let { if (it.isPlaying && it.duration > 0) updateTimeDisplay() }
        if (guiVisible && playerView.visibility == View.VISIBLE) resetGuiTimer()
        setImmersive()
    }

    override fun onPause() {
        // Cancel recurring timers to save battery
        timeUpdater?.let { handler.removeCallbacks(it); timeUpdater = null }
        guiTimer?.let { handler.removeCallbacks(it); guiTimer = null }
        player?.pause()
        glSurfaceView.onPause()
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        timeUpdater?.let { handler.removeCallbacks(it) }
        guiTimer?.let { handler.removeCallbacks(it) }
        // Release renderer on GL thread first, then player on UI thread
        glSurfaceView.queueEvent {
            renderer.release()
            runOnUiThread {
                videoSurface?.release()
                videoSurface = null
                player?.release()
                player = null
            }
        }
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setImmersive()
    }

    // ── Initialization ──

    private fun initViews() {
        rootLayout = findViewById(R.id.root)

        startScreen = findViewById(R.id.startScreen)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)

        playerView = findViewById(R.id.playerView)
        btnClose = findViewById(R.id.btnClose)
        guiBar = findViewById(R.id.guiBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSkipBack = findViewById(R.id.btnSkipBack)
        btnSkipFwd = findViewById(R.id.btnSkipFwd)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvDuration = findViewById(R.id.tvDuration)
        scrubBar = findViewById(R.id.scrubBar)
        btnVolume = findViewById(R.id.btnVolume)
        volumeSlider = findViewById(R.id.volumeSlider)
        btnSettings = findViewById(R.id.btnSettings)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        toastText = findViewById(R.id.toastText)
        spinner = findViewById(R.id.spinner)
        errorOverlay = findViewById(R.id.errorOverlay)
        errorText = findViewById(R.id.errorText)
        btnRetry = findViewById(R.id.btnRetry)
        settingsBackdrop = findViewById(R.id.settingsBackdrop)
        settingsPanel = findViewById(R.id.settingsPanel)
        btnSettingsClose = findViewById(R.id.btnSettingsClose)
        settingsBody = findViewById(R.id.settingsBody)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(2)

        // Wire buttons
        btnSelectVideo.setOnClickListener { pickVideo() }
        btnClose.setOnClickListener { closeVideo() }
        btnPlayPause.setOnClickListener { togglePlay() }
        btnSkipBack.setOnClickListener { skip(-10) }
        btnSkipFwd.setOnClickListener { skip(10) }
        btnVolume.setOnClickListener { toggleMute() }
        btnSettings.setOnClickListener { openSettings() }
        btnSettingsClose.setOnClickListener { closeSettings() }
        settingsBackdrop.setOnClickListener { closeSettings() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        btnRetry.setOnClickListener { pickVideo() }

        // Scrub bar
        scrubBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var isScrubbing = false
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isScrubbing) {
                    player?.let { p ->
                        val dur = p.duration
                        if (dur > 0) {
                            p.seekTo((dur * progress / 1000).toLong())
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isScrubbing = true; resetGuiTimer() }
            override fun onStopTrackingTouch(sb: SeekBar?) { isScrubbing = false }
        })

        // Volume slider
        volumeSlider.progress = (settings.volume * 100).toInt()
        updateVolumeIcon(settings.volume, settings.volume == 0f)
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val vol = progress / 100f
                    player?.volume = vol
                    settings.volume = vol
                    saveSettings()
                    updateVolumeIcon(vol, false)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // GUI auto-hide timer
        guiBar.setOnTouchListener { _, _ -> resetGuiTimer(); false }
    }

    private fun setupGL() {
        renderer = VrRenderer()
        renderer.onSurfaceReady = { surfaceTexture ->
            runOnUiThread { initPlayer(surfaceTexture) }
        }
        renderer.onRequestRender = {
            glSurfaceView.requestRender()
        }
        renderer.useGyro = settings.motionSensor
        renderer.fov = 75f
        renderer.isFisheye = settings.lensType == "fisheye"
        renderer.setProjection(settings.projection == "360")
        renderer.layout3d = settings.layout3d
        renderer.hCorrection = settings.hCorrection.toFloat()
        // Restore quality setting
        val (h, v) = when (settings.renderQuality) {
            "low" -> Pair(32, 20)
            "medium" -> Pair(48, 32)
            else -> Pair(60, 40)
        }
        renderer.setQuality(h, v)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    @UnstableApi
    private fun initPlayer(surfaceTexture: android.graphics.SurfaceTexture) {
        // Release previous player before creating a new one (GL context recreation)
        player?.release()
        videoSurface?.release()

        videoSurface = Surface(surfaceTexture)
        try {
            player = ExoPlayer.Builder(this)
                .build()
                .also { p ->
                    p.setVideoSurface(videoSurface)
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> { hideSpinner(); updatePlayPauseIcon(); updateTimeDisplay() }
                                Player.STATE_BUFFERING -> showSpinner()
                                Player.STATE_ENDED -> { updatePlayPauseIcon(); p.seekTo(0) }
                                else -> {}
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon()
                            if (isPlaying) hideSpinner()
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            hideSpinner()
                            showError("Playback error: ${error.localizedMessage}")
                        }
                    })
                    p.volume = settings.volume
                    p.playWhenReady = true
                }

            // Load video if one was picked before we had a player
            currentVideoUri?.let { uri ->
                player?.setMediaItem(MediaItem.fromUri(uri))
                player?.prepare()
            }
        } catch (e: Exception) {
            showError("Failed to initialize player: ${e.message}")
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationSensor == null) {
            hasGyro = false
            renderer.useGyro = false
            showToast("Gyroscope not available")
        }
    }

    private fun setupFilePicker() {
        // Already wired in initViews via btnSelectVideo
    }

    // ── SensorEventListener ──

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if (!hasGyro) {
                hasGyro = true
                if (settings.motionSensor) {
                    renderer.useGyro = true
                }
            }

            // Guard: don't push sensor data when motion sensor is turned off
            if (!settings.motionSensor) return

            // Get rotation matrix from sensor
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            // Remap for landscape display orientation.
            // windowManager.defaultDisplay.rotation returns Surface.ROTATION_*
            // (0=ROTATION_0, 1=ROTATION_90, 2=ROTATION_180, 3=ROTATION_270).
            @Suppress("DEPRECATION")
            val rot = windowManager.defaultDisplay.rotation
            val axisX: Int
            val axisY: Int
            when (rot) {
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    axisX = SensorManager.AXIS_Y
                    axisY = SensorManager.AXIS_Z
                }
                else -> {
                    axisX = SensorManager.AXIS_X
                    axisY = SensorManager.AXIS_Z
                }
            }
            // Write to a fresh local array each time — no data race with UI thread
            val freshMatrix = FloatArray(16)
            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, freshMatrix)
            renderer.sensorMatrix = freshMatrix

            // Request a new frame for the updated head tracking
            glSurfaceView.requestRender()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Touch handling ──

    private fun setupTouch() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.visibility == View.VISIBLE) {
                    toggleGui()
                }
                return true
            }
        })

        glSurfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            handleTouch(event)
            true
        }
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragStarted = false
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isDragging = false
                    isPinching = true
                    pinchDist0 = pinchDistance(event)
                    pinchFov0 = renderer.fov
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifted — return to single-finger drag mode
                if (event.pointerCount <= 2) {
                    isPinching = false
                    isDragging = true
                    dragStarted = false
                    lastTouchX = event.getX(0)
                    lastTouchY = event.getY(0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount == 2) {
                    val dist = pinchDistance(event)
                    val ratio = pinchDist0 / dist
                    renderer.fov = (pinchFov0 * ratio).coerceIn(30f, 150f)
                    glSurfaceView.requestRender()
                    return
                }

                if (isDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    if (!dragStarted && (Math.abs(dx) > 5f || Math.abs(dy) > 5f)) {
                        dragStarted = true
                    }

                    if (dragStarted && !renderer.useGyro) {
                        val inv = if (settings.invertControls) -1f else 1f
                        renderer.dragLon += dx * 0.3f * inv
                        renderer.dragLat += dy * 0.3f * inv
                        renderer.dragLat = renderer.dragLat.coerceIn(-85f, 85f)
                        glSurfaceView.requestRender()
                    }

                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isPinching = false
            }
        }
    }

    private fun pinchDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    // ── Video loading ──

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        videoPicker.launch(intent)
    }

    private fun loadVideo(uri: Uri) {
        currentVideoUri = uri
        hideError()

        startScreen.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        // Reset drag
        renderer.dragLon = 0f
        renderer.dragLat = 0f

        // Persist read access across reboots
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        // Load into player — re-establish the video surface if it was cleared
        player?.let { p ->
            p.stop()
            videoSurface?.let { p.setVideoSurface(it) }
            p.clearMediaItems()
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            showSpinner()
        }
    }

    private fun closeVideo() {
        player?.let { p ->
            p.stop()
            p.setVideoSurface(null)
        }
        currentVideoUri = null
        playerView.visibility = View.GONE
        startScreen.visibility = View.VISIBLE
        guiBar.visibility = View.VISIBLE
        guiVisible = true
    }

    // ── Playback controls ──

    private fun togglePlay() {
        player?.let { p ->
            if (p.isPlaying) {
                p.playWhenReady = false
                p.pause()
            } else {
                p.playWhenReady = true
                p.play()
            }
            updatePlayPauseIcon()
            resetGuiTimer()
        }
    }

    private fun skip(seconds: Int) {
        player?.let { p ->
            val pos = (p.currentPosition + seconds * 1000L).coerceIn(0L, p.duration)
            p.seekTo(pos)
            resetGuiTimer()
        }
    }

    private fun toggleMute() {
        player?.let { p ->
            p.volume = if (p.volume > 0f) 0f else settings.volume.coerceAtLeast(0.1f)
            updateVolumeIcon(p.volume, p.volume == 0f)
            volumeSlider.progress = if (p.volume > 0f) (p.volume * 100).toInt() else 0
        }
    }

    // ── GUI visibility ──

    private fun toggleGui() {
        guiVisible = !guiVisible
        guiBar.visibility = if (guiVisible) View.VISIBLE else View.GONE
        if (guiVisible) resetGuiTimer()
    }

    private fun resetGuiTimer() {
        guiTimer?.let { handler.removeCallbacks(it) }
        if (guiVisible) {
            val r = Runnable {
                if (!settingsPanel.isShown) {
                    guiVisible = false
                    guiBar.visibility = View.GONE
                }
            }
            guiTimer = r
            handler.postDelayed(r, GUI_TIMEOUT_MS)
        }
    }

    // ── Fullscreen ──

    private fun toggleFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val decor = window.decorView
            if (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                setImmersive()
            }
        }
    }

    // ── Settings ──

    data class VrSettings(
        var motionSensor: Boolean = true,
        var invertControls: Boolean = false,
        var lensType: String = "normal",
        var projection: String = "180",
        var layout3d: String = "sbs",
        var renderQuality: String = "high",
        var hCorrection: Int = 0,
        var volume: Float = 1f
    )

    private fun loadSettings() {
        settings = VrSettings(
            motionSensor = prefs.getBoolean("motionSensor", true),
            invertControls = prefs.getBoolean("invertControls", false),
            lensType = prefs.getString("lensType", "normal") ?: "normal",
            projection = prefs.getString("projection", "180") ?: "180",
            layout3d = prefs.getString("layout3d", "sbs") ?: "sbs",
            renderQuality = prefs.getString("renderQuality", "high") ?: "high",
            hCorrection = prefs.getInt("hCorrection", 0),
            volume = prefs.getFloat("volume", 1f)
        )
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putBoolean("motionSensor", settings.motionSensor)
            putBoolean("invertControls", settings.invertControls)
            putString("lensType", settings.lensType)
            putString("projection", settings.projection)
            putString("layout3d", settings.layout3d)
            putString("renderQuality", settings.renderQuality)
            putInt("hCorrection", settings.hCorrection)
            putFloat("volume", settings.volume)
            apply()
        }
    }

    private fun openSettings() {
        if (!settingsPanelBuilt) {
            buildSettingsPanel()
            settingsPanelBuilt = true
        }
        settingsBackdrop.visibility = View.VISIBLE
        settingsPanel.visibility = View.VISIBLE
        guiBar.visibility = View.GONE
    }

    private fun closeSettings() {
        settingsBackdrop.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        if (guiVisible) guiBar.visibility = View.VISIBLE
        resetGuiTimer()
    }

    private fun buildSettingsPanel() {
        settingsBody.removeAllViews()

        // ── Orientation ──
        addSectionTitle("Orientation")
        addSlider("Horizontal Correction", settings.hCorrection, -180, 180) { value ->
            settings.hCorrection = value
            renderer.hCorrection = value.toFloat()  // applied per-frame in renderer
            saveSettings()
        }

        // ── Motion Control ──
        addSectionTitle("Motion Control")
        addToggle("Motion Sensor", settings.motionSensor) { on ->
            settings.motionSensor = on
            renderer.useGyro = on
            if (!on) {
                renderer.sensorMatrix = null
            }
            saveSettings()
        }
        addToggle("Invert Controls", settings.invertControls) { on ->
            settings.invertControls = on
            saveSettings()
        }

        // ── Lens Type ──
        addSectionTitle("Lens Type")
        addRadio("lens", listOf("normal" to "Normal (Rectilinear)", "fisheye" to "Fisheye"),
            settings.lensType) { value ->
            settings.lensType = value
            renderer.isFisheye = value == "fisheye"
            saveSettings()
        }

        // ── Render Quality ──
        addSectionTitle("Render Quality")
        addRadio("quality", listOf(
            "high" to "High",
            "medium" to "Medium (smoother for 4K)",
            "low" to "Low (best performance)"
        ), settings.renderQuality) { value ->
            settings.renderQuality = value
            val qp = when (value) {
                "low" -> Pair(32, 20)
                "medium" -> Pair(48, 32)
                else -> Pair(60, 40)
            }
            renderer.setQuality(qp.first, qp.second)
            saveSettings()
        }

        // ── Projection ──
        addSectionTitle("Projection")
        addRadio("proj", listOf("180" to "180° Hemisphere", "360" to "360° Full Sphere"),
            settings.projection) { value ->
            settings.projection = value
            renderer.setProjection(value == "360")
            saveSettings()
        }

        // ── 3D Layout ──
        addSectionTitle("3D Layout")
        addRadio("lay", listOf(
            "mono" to "2D (Mono)",
            "sbs" to "Side by Side",
            "ou" to "Over / Under"
        ), settings.layout3d) { value ->
            settings.layout3d = value
            renderer.layout3d = value
            saveSettings()
        }
    }

    private fun addSectionTitle(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(0xff888888.toInt())
            textSize = 11f
            setPadding(0, 14, 0, 10)
            letterSpacing = 0.08f
            setTextAppearance(android.R.style.TextAppearance_Small)
            setAllCaps(true)
        }
        settingsBody.addView(tv)
    }

    private fun addToggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = layoutInflater.inflate(R.layout.setting_toggle, settingsBody, false)
        row.findViewById<TextView>(R.id.settingLabel).text = label
        val toggle = row.findViewById<Switch>(R.id.settingToggle)
        toggle.isChecked = initial
        toggle.setOnCheckedChangeListener { _, on -> onChange(on) }
        settingsBody.addView(row)
    }

    private fun addSlider(label: String, initial: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
        val row = layoutInflater.inflate(R.layout.setting_slider, settingsBody, false)
        row.findViewById<TextView>(R.id.settingLabel).text = label
        val valueTv = row.findViewById<TextView>(R.id.settingValue)
        val slider = row.findViewById<SeekBar>(R.id.settingSlider)
        slider.max = max - min
        slider.progress = initial - min
        valueTv.text = "$initial°"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                valueTv.text = "$v°"
                onChange(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        settingsBody.addView(row)
    }

    private fun addRadio(group: String, options: List<Pair<String, String>>,
                         selected: String, onChange: (String) -> Unit) {
        for ((value, label) in options) {
            val row = layoutInflater.inflate(R.layout.setting_radio, settingsBody, false)
            row.findViewById<TextView>(R.id.settingRadioLabel).text = label
            val radio = row.findViewById<RadioButton>(R.id.settingRadio)
            radio.isChecked = value == selected
            radio.setOnClickListener {
                // Uncheck all others in this group
                for (i in 0 until settingsBody.childCount) {
                    val child = settingsBody.getChildAt(i)
                    val rb = child.findViewById<RadioButton>(R.id.settingRadio) ?: continue
                    if (rb != radio && rb.tag == group) rb.isChecked = false
                }
                radio.tag = group
                onChange(value)
            }
            radio.tag = group
            settingsBody.addView(row)
        }
    }

    // ── UI helpers ──

    private fun updatePlayPauseIcon() {
        val isPlaying = player?.isPlaying == true
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateTimeDisplay() {
        timeUpdater?.let { handler.removeCallbacks(it) }
        val updater = object : Runnable {
            override fun run() {
                if (isFinishing) return
                player?.let { p ->
                    if (p.duration > 0) {
                        tvCurrentTime.text = formatTime(p.currentPosition)
                        tvDuration.text = formatTime(p.duration)
                        val progress = (p.currentPosition.toFloat() / p.duration * 1000).toInt()
                        scrubBar.progress = progress
                    }
                }
                handler.postDelayed(this, 250)
            }
        }
        timeUpdater = updater
        handler.post(updater)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).toInt()
        val m = total / 60
        val s = total % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }

    private fun updateVolumeIcon(vol: Float, muted: Boolean) {
        val v = if (muted) 0f else vol
        val icon = when {
            v == 0f -> R.drawable.ic_volume_mute
            v < 0.33f -> R.drawable.ic_volume_low
            v < 0.66f -> R.drawable.ic_volume_mid
            else -> R.drawable.ic_volume_high
        }
        btnVolume.setImageResource(icon)
    }

    private fun setImmersive() {
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.windowInsetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.systemBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ── Toast / spinner / error ──

    private fun showToast(msg: String) {
        toastText.text = msg
        toastText.visibility = View.VISIBLE
        toastText.postDelayed({ toastText.visibility = View.GONE }, 1000)
    }

    private fun showSpinner() { spinner.visibility = View.VISIBLE }
    private fun hideSpinner() { spinner.visibility = View.GONE }
    private fun showError(msg: String) { errorText.text = msg; errorOverlay.visibility = View.VISIBLE }
    private fun hideError() { errorOverlay.visibility = View.GONE }

    // ── Audio Focus ──

    private fun setupAudioFocus() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val afRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            player?.pause()
                            player?.playWhenReady = false
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            player?.volume = (player?.volume ?: 1f) * 0.3f
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            player?.volume = settings.volume
                        }
                    }
                }
                .build()
            audioFocusRequest = afRequest
            audioManager?.requestAudioFocus(afRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    // ── Permissions ──

    private fun requestStoragePermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1002)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        // Proceed regardless — SAF file picker works without storage permission
    }
}
