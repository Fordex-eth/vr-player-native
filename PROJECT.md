# VR Player Native — Complete Project Documentation

> **Purpose of this document:** A self-contained reference for the codebase so you (or another dev) can rebuild the UI without having to re-derive the architecture from scratch.

---

## 1. Project Overview

**VR Player Native** is an Android app that plays Side-by-Side (SBS), Over-Under (OU), and Mono 180° / 360° VR videos, projecting each frame onto the inside of a sphere in OpenGL ES 2.0. The user looks around by tilting the device (gyro head-tracking) or by dragging the screen (touch fallback). It is a complete rewrite of a previous WebView + Three.js implementation, replacing JavaScript overhead with native ExoPlayer + GLSurfaceView for smooth 60fps playback.

### Key Properties

| Property | Value |
|---|---|
| Language | Kotlin 2.0.21 |
| Min SDK | 24 (Android 7.0) |
| Target / Compile SDK | 35 (Android 15) |
| Renderer | OpenGL ES 2.0 via `GLSurfaceView` |
| Video | ExoPlayer (Media3) 1.5.1 |
| Java target | 11 |
| Package | `com.vrplayer.app` |
| Repo | `https://github.com/Fordex-eth/vr-player-native` |

---

## 2. File Map

```
app/src/main/
├── AndroidManifest.xml                 Permissions, fullscreen, landscape, icon
├── java/com/vrplayer/app/
│   ├── MainActivity.kt                 ★ Orchestrator: views, player, sensors, settings
│   └── VrRenderer.kt                   ★ GLSurfaceView.Renderer: sphere, matrix math, Y-flip
└── res/
    ├── drawable/                       Vector icons, button backgrounds, GUI bar gradient
    │   ├── ic_play.xml / ic_pause.xml
    │   ├── ic_skip_back.xml / ic_skip_fwd.xml
    │   ├── ic_volume_low/mid/high/mute.xml
    │   ├── ic_close.xml / ic_gear.xml / ic_fullscreen.xml
    │   ├── btn_primary.xml             Rounded button background
    │   ├── bg_gui_bar.xml              Top-to-bottom gradient
    │   ├── bg_toast.xml                Rounded semi-transparent
    │   ├── bg_circle_50.xml            Round close button
    │   ├── scrubber_progress.xml       Horizontal seekbar
    │   └── vol_progress.xml            Volume slider
    ├── layout/
    │   ├── activity_main.xml           ★ The full screen — GLSurfaceView + GUI bar + settings panel
    │   ├── setting_toggle.xml          Switch row (Motion Sensor, Invert Controls)
    │   ├── setting_slider.xml          SeekBar row (Horizontal Correction)
    │   └── setting_radio.xml           RadioButton row (Lens, Quality, Projection, Layout)
    ├── mipmap-*/                       Launcher icons (mdpi → xxxhdpi)
    │   ├── ic_launcher.png             Standard icon
    │   └── ic_launcher_foreground.png  Padded foreground for adaptive icon
    ├── values/
    │   ├── colors.xml
    │   ├── strings.xml
    │   └── themes.xml
    └── xml/
        └── file_paths.xml              (none currently)
```

---

## 3. Architecture — The Big Picture

```
            ┌──────────────────────────────────────────────┐
            │            MainActivity (UI thread)           │
            │                                               │
   User ───►│  Touch ───► handleTouch()                    │
   taps     │              │                                │
            │              ├─ Single tap  → toggleGui()     │
            │              ├─ Double tap  → togglePlay()    │
            │              ├─ Drag        → dragLon/dragLat │
            │              ├─ Pinch       → renderer.fov    │
            │              └─ Hold 500ms  → motionPaused UI │
            │                                               │
            │  Gyro ────► onSensorChanged()                 │
            │              │                                │
            │              ├─ hasGyro flag                  │
            │              ├─ rotationMatrix                │
            │              ├─ remapCoordinateSystem         │
            │              └─ renderer.sensorMatrix = …     │
            │                                               │
            │  Settings ► buildSettingsPanel()              │
            │              │                                │
            │              ├─ Horizontal Correction slider  │
            │              ├─ Motion Sensor toggle           │
            │              ├─ Invert Controls toggle        │
            │              ├─ Lens (normal/fisheye)          │
            │              ├─ Quality (low/med/high)        │
            │              ├─ Projection (180/360)          │
            │              └─ 3D Layout (mono/sbs/ou)        │
            └────────────┬──────────────────────────────────┘
                         │   writes to renderer.useGyro,
                         │   renderer.fov, renderer.sensorMatrix, etc.
                         ▼
            ┌──────────────────────────────────────────────┐
            │   VrRenderer (GL thread, RENDERMODE_WHEN_DIRTY)│
            │                                               │
            │  onDrawFrame():                               │
            │    1. Clear                                   │
            │    2. Rebuild sphere if geoVersion changed    │
            │    3. perspectiveM(fov)                       │
            │    4. View matrix from sensor or drag         │
            │    5. updateTexImage() ← ExoPlayer frames     │
            │    6. Apply Y-flip correction                 │
            │    7. layoutTransform * texMatrix            │
            │    8. DrawElements on sphere                  │
            └────────────┬──────────────────────────────────┘
                         │   SurfaceTexture (OES texture)
                         ▼
            ┌──────────────────────────────────────────────┐
            │   ExoPlayer (Media3) — decodes H.264/H.265,   │
            │   writes video frames to the SurfaceTexture    │
            └──────────────────────────────────────────────┘
```

### Threading model

| Thread | What runs on it |
|---|---|
| UI thread | `onCreate`, touch events, `onSensorChanged`, settings toggles, `runOnUiThread` callbacks |
| GL thread | `onSurfaceCreated`, `onSurfaceChanged`, `onDrawFrame` |
| Frame-available callback thread | `synchronized(lock) { frameAvailable = true }` from SurfaceTexture |
| ExoPlayer internal threads | Decode, audio rendering |

Synchronization between threads uses a single private `lock` object inside `VrRenderer`. Fields written by the UI thread and read by the GL thread use `@Volatile` or `synchronized(lock)`.

---

## 4. Core Algorithms

### 4.1 Sphere projection

`VrRenderer.buildSphere()` generates a UV sphere. For 180° projection, it covers only half the sphere (`phiStart = π, phiLength = π`). For 360°, it covers the full sphere.

```kotlin
phiStart = if (projection360) 0f else PI
phiLength = if (projection360) 2*PI else PI
```

The sphere is rendered as triangles. The camera sits at the origin (0, 0, 0) inside the sphere. The radius is 500 units, with near/far planes at 0.1 and 1000, so the camera is well inside the sphere and the view is unobstructed.

**Culling:** `GL_CULL_FACE` with `GL_FRONT_FACE = GL_CW` — only the inside surface of the sphere is drawn (back-face culled). This is critical: the camera is *inside* the sphere, so we want to see the *back* side from the inside. Vertex winding is generated to be CW when viewed from the inside.

### 4.2 Y-flip correction

`SurfaceTexture.getTransformMatrix()` returns a matrix that flips Y (Android graphics buffer convention: y=0 at bottom). Sphere textures expect y=0 at the top. We undo this in-place:

```kotlin
texMatrix[5]  = -texMatrix[5]      // negate Y scale
texMatrix[13] = 1f - texMatrix[13]  // adjust Y offset
```

**Critical:** This modification is **inside** the `if (frameAvailable)` block. If it ran every frame, it would re-negate an already-negated matrix on frames where `getTransformMatrix` did not run, causing alternating correct/inverted frames → flickering.

### 4.3 Sensor → view matrix

```kotlin
val sm = synchronized(lock) { sensorMatrix }
if (useGyro && sm != null) {
    Matrix.transposeM(viewMatrix, 0, sm, 0)   // sensor rot^T = inverse = camera view
    if (hCorrection != 0f) {
        Matrix.rotateM(viewMatrix, 0, hCorrection, 0f, 1f, 0f)
    }
}
```

The sensor matrix is a device-to-world rotation. Its transpose (which equals its inverse for a rotation matrix) is the world-to-camera transform, which is the view matrix.

**Coordinate remapping** is done in the activity (per-display-rotation), not in the renderer, so the renderer just consumes a screen-aligned matrix.

### 4.4 Touch drag (non-gyro fallback)

When `useGyro == false`:
```kotlin
Matrix.rotateM(viewMatrix, 0, dragLat, 1f, 0f, 0f)
Matrix.rotateM(viewMatrix, 0, dragLon, 0f, 1f, 0f)
```

These accumulate as the user drags. `dragLat` is clamped to ±85° to avoid gimbal-lock at the poles.

### 4.5 SBS / OU / Mono layout

`updateLayoutTransform()` sets a 2D matrix that's multiplied with the texture matrix in the shader. For SBS, scale x by 0.5 (left eye). For OU, scale y by 0.5 and translate by +0.5 (top eye). Mono is identity.

### 4.6 FOV / pinhole model

```kotlin
Matrix.perspectiveM(projectionMatrix, 0, fov, aspect, 0.1f, 1000f)
```

FOV range: 30°–150° (clamped on pinch). Fisheye mode forces 140°. Default is 75°.

---

## 5. Lifecycle and the "Stall Recovery" Discovery

This is the story of the most interesting bug we caught. **The video plays for 20–30 seconds, then stops mid-playback. Switch to another app, come back, and it plays perfectly.** Here's what was happening:

### The race

```
Frame N arrives → callback fires → frameAvailable = true   (NO LOCK)
GL thread:
  synchronized(lock) {
    if (frameAvailable) {                                  ← reads true
      updateTexImage()                                    ← consumes N
      // ← ANOTHER callback can fire HERE
      frameAvailable = false                               ← OVERWRITES the new notification
    }
  }
```

Lost notifications accumulate. After ~1 second the SurfaceTexture's internal buffer queue fills. The decoder blocks on `dequeueOutputBuffer`. Eventually the decoder times out and the video stalls.

Switching to another app destroys the GL context. Returning creates a brand new SurfaceTexture and ExoPlayer, with empty buffers, so the issue is reset.

### The fix (two parts)

1. **Synchronize the callback write** with the same lock the GL thread uses:
   ```kotlin
   surfaceTexture?.setOnFrameAvailableListener {
       synchronized(lock) { frameAvailable = true }
       onRequestRender?.invoke()
   }
   ```
2. **Use `RENDERMODE_WHEN_DIRTY`** instead of `RENDERMODE_CONTINUOUSLY`. Continuous rendering was just papering over the race by rendering aggressively. `WHEN_DIRTY` uses ~1/60th the GPU power, which is the real fix for 1080p+ (no thermal throttling).

`RENDERMODE_WHEN_DIRTY` requires explicit `requestRender()` calls everywhere the state changes: frame available, sensor change, pinch, drag. These are wired via the `onRequestRender` callback.

---

## 6. Settings Persistence

All settings are stored in `SharedPreferences("vrplayer", MODE_PRIVATE)` as key-value pairs:

| Key | Type | Default |
|---|---|---|
| `motionSensor` | Boolean | **true** |
| `invertControls` | Boolean | **true** |
| `lensType` | String ("normal" / "fisheye") | "normal" |
| `projection` | String ("180" / "360") | "180" |
| `renderQuality` | String ("low" / "med" / "high") | "high" |
| `layout3d` | String ("mono" / "sbs" / "ou") | "sbs" |
| `hCorrection` | Int (-180 to 180) | 0 |
| `volume` | Float (0.0 to 1.0) | 1.0 |

`loadSettings()` reads them at `onCreate`; `saveSettings()` is called after every change. The `VrSettings` data class is a mutable in-memory cache.

---

## 7. UI Layout — The Three Layers

The single `activity_main.xml` uses a `FrameLayout` (root) with three nested layers:

### Layer 1 — GLSurfaceView
Full-screen OpenGL surface. Always present.

### Layer 2 — Start screen
Visible until a video is picked. Contains the app title, subtitle, and "Select Video" button.

### Layer 3 — Player view
Container `FrameLayout` (visibility="gone" by default). Contains:
- **Close button** (top-left)
- **Toast** (center, transient)
- **Spinner** (center, while buffering)
- **Motion Paused** (top-center, during touch-hold)
- **Error overlay** (full screen, with retry button)
- **GUI bar** (bottom, slides up/down, contains transport + scrubber + volume + settings + fullscreen)
- **Settings backdrop** (full screen, semi-transparent)
- **Settings panel** (bottom, slides up, contains ScrollView with programmatic rows)

### Slide animations
- GUI bar: 250ms translateY
- Settings panel: 300ms translateY (200ms GUI bar sink at open, 200ms rise at close)

---

## 8. Touch Gestures

| Gesture | Effect |
|---|---|
| Single tap | Toggle GUI bar visibility |
| Double tap | Toggle play/pause |
| One-finger drag | Drag rotation (only when `useGyro == false`) |
| Two-finger pinch | Adjust FOV (30°–150°) |
| Touch-hold 500ms | Pause motion tracking, show "Motion Paused" |

The 5px dead-zone prevents accidental drags from accidental taps. The touch-hold timer is cancelled if the user moves their finger >5px (treated as a drag, not a hold).

---

## 9. Code Flow — Loading a Video

```
User taps "Select Video"
  └─► ACTION_OPEN_DOCUMENT intent
        └─► SAF picker
              └─► loadVideo(uri)
                    ├─ hideError()
                    ├─ playerView.VISIBLE
                    ├─ guiBar.post { translateY = h; animate().translationY(0).duration(250) }
                    ├─ resetGuiTimer()
                    ├─ renderer.dragLon/dragLat = 0
                    ├─ renderer.fov = 75f   ← reset every load
                    ├─ takePersistableUriPermission(uri)
                    └─ player.let {
                         it.stop()
                         it.setVideoSurface(videoSurface)  ← re-attach if needed
                         it.clearMediaItems()
                         it.setMediaItem(MediaItem.fromUri(uri))
                         it.prepare()
                         showSpinner()
                       }

ExoPlayer decodes frames
  └─► writes to SurfaceTexture
        └─► onFrameAvailable callback (synchronized lock)
              └─► onRequestRender() → glSurfaceView.requestRender()
                    └─► GL thread: onDrawFrame()
                          └─► updateTexImage() → render sphere
```

If the GL surface isn't ready yet when the user picks a video, `player` is null. The URI is stored in `currentVideoUri`. When `initPlayer` is called later (from the GL surface ready callback), it picks up the stored URI.

---

## 10. Audio

`AudioFocusRequest` is used on API 26+. The focus change handler:
- `LOSS` → pause
- `LOSS_TRANSIENT` → pause
- `LOSS_TRANSIENT_CAN_DUCK` → volume to 30%
- `GAIN` → restore volume

On API < 26, the legacy `requestAudioFocus(null, STREAM_MUSIC, AUDIOFOCUS_GAIN)` is used.

---

## 11. Permissions

`requestStoragePermission()` requests `READ_MEDIA_VIDEO` on API 33+ or `READ_EXTERNAL_STORAGE` on older devices. The result is currently ignored because the SAF file picker works without storage permission. The request is still sent for documentation purposes.

---

## 12. Diagnostic Logging

Use `adb logcat -s VRPlayer` to see:
- Sensor availability
- First rotation_vector event
- Video size and codec (from `onVideoSizeChanged` / `onTracksChanged`)
- Playback errors with `errorCodeName`
- Frame stall warnings (if no new frame for 2+ seconds while playing)

---

## 13. Known Hardware Limitations

- **No gyroscope**: Some devices (older tablets, budget phones) lack `TYPE_ROTATION_VECTOR`. A toast "Gyroscope not available — using touch controls" is shown. The user can still drag.
- **Decoder caps**: Some devices cap hardware decoding at 720p. 1080p+ videos will fail with `NO_EXCEEDS_CAPABILITIES`. The error overlay suggests using H.264 instead of H.265.
- **No software decoder fallback**: We rely on the device's hardware MediaCodec. If a codec isn't supported in hardware and there's no software fallback in the platform, the video won't play.

---

## 14. Build & Run

```bash
# Clone
git clone https://github.com/Fordex-eth/vr-player-native.git
cd vr-player-native

# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app launches in landscape, immediately immersive (no system bars), and shows the start screen.

---

## 15. UI Improvement Notes (for a redesign)

The current UI is a pragmatic translation from the original HTML version, not a designed experience. Areas that would benefit from a redesign:

1. **Typography**: Currently uses the platform default at small sizes. Consider a custom typeface with tighter spacing.
2. **Button hierarchy**: Transport buttons (skip/play/skip) are equal weight. The play button could be visually primary with larger hit area.
3. **Settings panel**: Currently a single-column scroll with all options stacked. Grouping into collapsible cards with icons would help scannability.
4. **Color palette**: Currently uses near-black grays (`#1a1a1a`, `#000`, `#888`) with white text. A more deliberate dark theme with accent colors would feel more polished.
5. **Touch targets**: All icons are 44-48dp, which meets Material guidelines but feels cramped in a transport row. Consider 56-64dp.
6. **Animation timing**: The 250ms / 300ms values are arbitrary. Coordinated cubic-bezier curves (e.g., `FastOutSlowIn`) would feel more refined.
7. **Gesture affordance**: There's no hint that touch-hold does anything. A brief tooltip or a "release to resume" hint on first use would help discoverability.
8. **Empty state**: The start screen is minimal. Could include "Open Recent" or "Browse Files" as larger touch targets.
9. **Settings persistence**: Currently all-or-nothing via SharedPreferences. A "Reset to defaults" button would be nice.
10. **Accessibility**: Currently zero TalkBack labels, no content descriptions on most icons, no haptic feedback on touch-hold.

The architecture supports UI changes without touching the renderer or player logic. Most changes are confined to:
- `activity_main.xml` (restructure the layout)
- `MainActivity.kt` (re-wire listeners, update animation logic)
- `res/drawable/` and `res/values/` (visual assets)

The core render loop, decoder pipeline, and settings persistence do not need to change for a UI redesign.
