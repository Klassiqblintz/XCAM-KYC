# 📸 xCam-KYC — Universal Virtual Camera Bypass

A Jetpack Compose Kotlin app that injects virtual camera media (videos/images) into any KYC/verification app's camera pipeline — **without APK patching**.

Built using the **[xCam](https://github.com/hazbu/xCam)** architecture, adapted as a standalone Xposed module that runs per-process.

## 🚀 Key Features

- **Universal Camera Injection** — Works with any app using Camera1, Camera2, or ImageReader APIs
- **Photo Capture Replacement** — Every photo taken is replaced with a frame from your virtual media
- **Video Recording Replacement** — Recorded video comes from your source, not the physical camera
- **WebRTC Support** — Works in video call apps (Zoom, Google Meet, etc.)
- **Audio Injection** — Replaces microphone input with audio from your media source
- **Live Pipeline Monitor** — Shows real-time camera pipeline routes (`ImageReader → Camera2 → MediaRecorder`)
- **Rotations & Mirroring** — Apply 90°/180°/270° rotation and horizontal flip to virtual camera
- **Non-Root Friendly** — Use with LSPatch (patch this APK) or LSPosed (rooted)

## 📁 Project Structure

```
app/src/main/java/com/hazbu/xcam/
├── data/
│   ├── Constants.kt              # All constants, ContentProvider authority
│   └── SettingsProvider.kt       # Cross-process bridge (query media, receive telemetry)
│
├── hooks/  ← THE INJECTION LAYER (Java)
│   ├── Camera2Hook.java          # Camera2 API — discovery, hijack, surgical diversion
│   ├── CameraHook.java           # Legacy Camera1 API
│   ├── ImageReaderHook.java      # Native ImageReader (YUV420/JPEG/RGBA)
│   ├── CaptureHook.java          # Photo capture (BitmapFactory, compress, MediaStore)
│   ├── WebRtcHook.java           # WebRTC video calls
│   └── AudioHook.java            # AudioRecord → virtual PCM audio
│
├── core/  ← THE ENGINE (Kotlin)
│   ├── engine/
│   │   ├── XCamEngine.kt         # Dispatcher — feeds ExoPlayer into target surfaces
│   │   └── MediaEngine.kt        # ExoPlayer/ Media3 — loads video, sets surface, loops
│   ├── surface/
│   │   ├── SurfaceManager.kt     # Surface ID tracking, preview vs ImageReader
│   │   └── SurfaceProvider.kt    # Dummy SurfaceTexture to divert original camera
│   ├── capture/
│   │   ├── CaptureManager.kt     # Frame extraction for stills & streaming
│   │   ├── XCamCapture.kt        # JPEG creation from video at exact timestamp
│   │   ├── MediaCodecYuvDecoder.kt
│   │   └── YuvFrameProcessor.kt
│   ├── audio/
│   │   └── MediaCodecAudioDecoder.kt
│   ├── telemetry/
│   │   └── PipelineTracker.kt    # Live pipeline route monitoring
│   └── settings/
│       └── SettingsManager.kt
│
├── xposed/
│   ├── XCamInjectors.kt          # Installs all 6 hooks per target app
│   └── XCamModule.kt             # Main module — coordinates everything
│
└── ui/
    ├── MainActivity.kt           # Jetpack Compose — app picker, media, injection, monitor
    ├── BypassService.kt          # Foreground service (runs in each target's process)
    └── CaptureNotificationHelper.kt
```

## 🔧 How It Works

1. **Select a target app** from the list of installed camera-using apps
2. **Pick a media file** (video/image) from your gallery
3. **Press START INJECTION** — the service hooks into the target app's process
4. **Open the target app** — its camera now shows your virtual media
5. **Every photo taken** is replaced with a frame from your media
6. **Live pipeline monitor** shows which camera pipeline nodes are active

### The Pipeline

```
Your Video ──► ExoPlayer ──► Target App's Surface
                                     │
                         ┌───────────┼───────────┐
                         ▼           ▼           ▼
                    Camera1      Camera2    ImageReader
                    Preview     Sessions   AcquireFrame
                                     │
                         ┌───────────┼───────────┐
                         ▼           ▼           ▼
                 SurfaceTexture  OutputConfig  YUV420
                 DummyTexture    Hijacked      Replaced
```

## 📦 Building

Requires Android Studio or command-line Gradle build:

```bash
git clone https://github.com/Klassiqblintz/XCAM-KYC.git
cd XCAM-KYC
./gradlew assembleDebug
```

## 📱 Usage

### Rooted (LSPosed/KernelSU)
1. Install xCam-KYC as a module
2. Enable in Xposed installer
3. Open the app → select target + media → inject

### Non-Rooted (LSPatch)
1. Patch xCam-KYC APK with LSPatch
2. Install the patched APK
3. Open → select target + media → inject

## 🎯 Target Apps

Works with any app using Android Camera APIs:
- **KYC**: Hyperverge, Veriff, Onfido, Jumio, Mverify
- **Banking**: Any KYC-enabled banking app
- **Social**: Instagram, Snapchat, Facebook
- **Video Calls**: Zoom, Google Meet, WhatsApp
- **Any app with a camera**

## ⚙️ Dependencies

| Library | Purpose |
|---|---|
| LibXposed API 101 | Xposed module framework |
| Media3/ExoPlayer 1.4.1 | Video playback & surface injection |
| Material3 + Compose | Jetpack Compose UI |

## 📄 License

GPL-3.0

## ⚠️ Disclaimer

Designed for educational, testing, and development purposes. Use responsibly — some KYC providers may detect virtual camera input through optical/behavioral analysis.

---

**Built with ❤️ using the xCam architecture from [hazbu/xCam](https://github.com/hazbu/xCam)**
