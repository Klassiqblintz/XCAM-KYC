package com.hazbu.xcam.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import com.hazbu.xcam.core.audio.MediaCodecAudioDecoder
import com.hazbu.xcam.core.capture.CaptureManager
import com.hazbu.xcam.core.capture.MediaCodecYuvDecoder
import com.hazbu.xcam.core.capture.YuvFrameProcessor
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider
import com.hazbu.xcam.core.telemetry.PipelineTracker
import com.hazbu.xcam.hooks.*
import com.hazbu.xcam.utils.Logger
import com.hazbu.xcam.utils.SystemUtils
import com.hazbu.xcam.utils.UIUtils
import com.hazbu.xcam.data.Constants
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import android.os.SystemClock
import java.io.ByteArrayInputStream

@UnstableApi
class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    @Volatile private var yuvDecoder: MediaCodecYuvDecoder? = null
    @Volatile private var audioDecoder: MediaCodecAudioDecoder? = null
    private val yuvLock = Any()
    private val audioLock = Any()

    private val cameraHook = CameraHook(this)
    private val camera2Hook = Camera2Hook(this)
    private val imageReaderHook = ImageReaderHook(this)
    private val captureHook = CaptureHook(this, imageReaderHook)
    private val webRtcHook = WebRtcHook(this)
    private val audioHook = AudioHook(this)
    private val injectors = XCamInjectors(this, cameraHook, camera2Hook, imageReaderHook, captureHook, webRtcHook, audioHook)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine { printLog(it) }
    private val yuvProcessor = YuvFrameProcessor()

    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settings.refreshSettings(it) },
    ) { printLog(it) }

    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settings },
        surfaceManager = surfaceManager,
        mediaEngine = mediaEngine,
        surfaceProvider = surfaceProvider,
    ) { printLog(it) }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }

    val context: Context? get() = mContext
    val mediaPath: String? get() = settings.mediaPath
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        if (tr != null || (msg.contains("Error") || msg.contains("failed") || msg.contains("FATAL"))) {
            Logger.e(this, msg, tr)
        } else {
            Logger.i(this, msg)
        }
    }

    fun logInit(msg: String) = Logger.i(this, "[INIT] $msg")
    fun logHook(msg: String) = Logger.d(this, "[HOOK] $msg")

    fun showToast(message: String?) {
        if (message.isNullOrEmpty()) return
        UIUtils.showToast(mContext, message!!) { msg -> printLog(msg ?: "") }
    }

    fun isCapturingState() = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition().toInt() }
    }

    fun getCurrentPosition(): Long = engine.getCurrentPosition()

    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)
    fun registerImageReaderSurface(s: Surface, f: Int, w: Int, h: Int) = surfaceManager.registerImageReaderSurface(s, f, w, h)
    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)
    fun isNonPreviewSurface(s: Surface?) = surfaceManager.isNonPreviewSurface(s)
    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)
    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    fun recordPipelineNode(node: String) {
        PipelineTracker.recordNode(mContext, node)
    }

    @JvmOverloads
    fun reportPipelineCapture(width: Int = 0, height: Int = 0, thumbnail: ByteArray? = null) {
        PipelineTracker.reportCapture(mContext, width, height, thumbnail)
    }

    private fun getOrCreateYuvDecoder(ctx: Context, path: String): MediaCodecYuvDecoder {
        return yuvDecoder ?: synchronized(yuvLock) {
            yuvDecoder ?: MediaCodecYuvDecoder(ctx, path) { printLog(it) }.apply { start() }.also { yuvDecoder = it }
        }
    }

    private fun getOrCreateAudioDecoder(ctx: Context, path: String): MediaCodecAudioDecoder {
        return audioDecoder ?: synchronized(audioLock) {
            audioDecoder ?: MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }.also { audioDecoder = it }
        }
    }

    fun injectYuvFrame(image: android.media.Image, width: Int, height: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        if (path.lowercase().endsWith(".mp4")) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.getNextFrame()
            if (frame != null) {
                val bitmap = yuvProcessor.process(frame, settings.rotationAngle, settings.isMirrored)
                if (bitmap != null) {
                    val buffer = image.planes[0].buffer
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, ByteArrayOutputStream(buffer))
                    bitmap.recycle()
                }
            }
        }
    }

    fun handleCapture(width: Int, height: Int): ByteArray? {
        val path = settings.mediaPath ?: return null
        return captureManager.handleCapture(
            path = path,
            width = width,
            height = height,
            rotationAngle = settings.rotationAngle,
            isMirrored = settings.isMirrored,
            maxSizeBytes = 256 * 1024,
            isIgnoringHooks = { isIgnoringHooks() },
            setIgnoringHooks = { setIgnoringHooks(it) }
        )
    }

    @JvmOverloads
    fun handleCapture(
        width: Int = 0,
        height: Int = 0,
        rotationAngle: Int = 0,
        isMirrored: Boolean = false
    ): ByteArray? {
        val path = settings.mediaPath ?: return null
        val ctx = mContext ?: return null

        return captureManager.handleCapture(
            path = path,
            width = width,
            height = height,
            rotationAngle = rotationAngle,
            isMirrored = isMirrored,
            maxSizeBytes = 256 * 1024,
            isIgnoringHooks = { isIgnoringHooks() },
            setIgnoringHooks = { setIgnoringHooks(it) }
        )
    }

    fun handleStreamFrame(): ByteArray? {
        val path = settings.mediaPath ?: return null
        val ctx = mContext ?: return null

        return captureManager.handleStreamFrame(
            path = path,
            width = 0,
            height = 0,
            rotationAngle = settings.rotationAngle,
            isMirrored = settings.isMirrored,
            isIgnoringHooks = { isIgnoringHooks() },
            setIgnoringHooks = { setIgnoringHooks(it) }
        )
    }

    fun injectAudioBytes(buffer: ByteArray, offset: Int, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        val decoder = getOrCreateAudioDecoder(ctx, path)
        val frame = decoder.getNextAudioFrame()
        if (frame != null && frame.isNotEmpty()) {
            val copyLen = minOf(frame.size, length)
            System.arraycopy(frame, 0, buffer, offset, copyLen)
        }
    }

    fun injectAudioShorts(buffer: ShortArray, offset: Int, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        val decoder = getOrCreateAudioDecoder(ctx, path)
        val frame = decoder.getNextAudioFrame()
        if (frame != null && frame.isNotEmpty()) {
            val shortCount = minOf(length, frame.size / 2)
            for (i in 0 until shortCount) {
                buffer[offset + i] = ((frame[i * 2].toInt() and 0xFF) or (frame[i * 2 + 1].toInt() and 0xFF) shl 8).toShort()
            }
        }
    }

    fun injectAudioBuffer(buffer: ByteBuffer, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        val decoder = getOrCreateAudioDecoder(ctx, path)
        val frame = decoder.getNextAudioFrame()
        if (frame != null && frame.isNotEmpty()) {
            val copyLen = minOf(frame.size, length)
            buffer.put(frame, 0, copyLen)
        }
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        engine.handleCamera1Preview(st)
    }

    fun handleModernPreview(surface: Surface) {
        engine.handleModernPreview(surface)
    }

    fun getDummySurface(): Surface = engine.getDummySurface()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        logInit("xCam module started (process: ${param.processName})")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        logInit("xCam package ready: ${param.packageName}")
        if (mContext == null) {
            try {
                val cls = Class.forName("android.app.ActivityThread")
                val method = cls.getMethod("currentApplication")
                val app = method.invoke(null) as? android.app.Application
                if (app != null) {
                    mContext = app.applicationContext
                }
            } catch (_: Throwable) {
                // ActivityThread not available, mContext stays null
            }
        }
        if (param.packageName == mContext?.packageName) return
        if (hooksInstalled) return

        try {
            injectors.install(param)
            hooksInstalled = true
        } catch (e: Throwable) {
            logHook("[!] Failed to install hooks: ${e.message}")
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        logHook("onPackageLoaded: ${param.packageName}")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        logHook("onSystemServerStarting")
    }
}

private class ByteArrayOutputStream(private val buf: java.nio.ByteBuffer) : java.io.OutputStream() {
    override fun write(b: Int) {
        buf.put(b.toByte())
    }

    override fun write(b: ByteArray) {
        buf.put(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buf.put(b, off, len)
    }
}
