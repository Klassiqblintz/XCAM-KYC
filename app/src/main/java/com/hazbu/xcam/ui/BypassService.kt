package com.hazbu.xcam.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider
import com.hazbu.xcam.core.telemetry.PipelineTracker
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.Logger
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Timer
import java.util.TimerTask

/**
 * Foreground service that runs in each target app's process.
 * Coordinates all hooks and engine components.
 */
@UnstableApi
class BypassService : Service(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "xBypass"
        private const val NOTIFICATION_ID = Constants.SERVICE_NOTIFICATION_ID
        private const val CHANNEL_ID = Constants.SERVICE_NOTIFICATION_CHANNEL_ID
    }

    private var mContext: Context? = null
    private var mPackageManager: PackageManager? = null

    private lateinit var settings: SettingsManager
    private lateinit var surfaceManager: SurfaceManager
    private lateinit var surfaceProvider: SurfaceProvider
    private lateinit var mediaEngine: MediaEngine
    private lateinit var engine: XCamEngine
    private lateinit var captureNotificationHelper: CaptureNotificationHelper

    private var isBound = false
    private var currentTargetPackage: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 500L
    private var updateTask: TimerTask? = null

    private var xposedService: XposedService? = null
    private var xposedBinder: android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mContext = this
        mPackageManager = packageManager

        settings = SettingsManager()
        surfaceManager = SurfaceManager { log(it) }
        surfaceProvider = SurfaceProvider { log(it) }
        mediaEngine = MediaEngine { log(it) }
        engine = XCamEngine(
            contextProvider = { this@BypassService },
            settingsProvider = { settings },
            surfaceManager = surfaceManager,
            mediaEngine = mediaEngine,
            surfaceProvider = surfaceProvider,
        ) { log(it) }

        settings.refreshSettings(this)
        startForegroundService()
        log("[+] BypassService created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (xposedBinder == null) {
            val service = io.github.libxposed.service.XposedService(this)
            xposedService = service
            xposedBinder = service.getBinder()
        }
        return xposedBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START" -> {
                val targetPackage = intent.getStringExtra("TARGET_PACKAGE")
                val mediaPath = intent.getStringExtra("MEDIA_PATH")
                val mirrored = intent.getBooleanExtra("MIRRORED", false)
                val rotation = intent.getIntExtra("ROTATION", 0)

                if (targetPackage != null && mediaPath != null) {
                    currentTargetPackage = targetPackage
                    settings.mediaPath = mediaPath
                    settings.isMirrored = mirrored
                    settings.rotationAngle = rotation

                    startInjection(targetPackage)
                }
            }
            "ACTION_STOP" -> {
                stopInjection()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startInjection(targetPackage: String) {
        log("[*] Starting injection for package: $targetPackage")
        updatePipelineRoute()
        startPositionPolling()
    }

    private fun stopInjection() {
        log("[*] Stopping injection")
        engine.stop()
        mediaEngine.stop()
        stopPositionPolling()
    }

    private fun startPositionPolling() {
        updateTask = object : TimerTask() {
            override fun run() {
                handler.post { updatePipelineRoute() }
            }
        }
        Timer("xBypass-update", true).schedule(updateTask, 0, updateInterval)
    }

    private fun stopPositionPolling() {
        updateTask?.cancel()
        updateTask = null
    }

    private fun updatePipelineRoute() {
        val route = PipelineTracker.getRouteSummary()
        log("[PIPELINE] Route: $route")
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "xCam Bypass Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera injection service"
                setSound(null, null)
                setVibrationPattern(longArrayOf(0))
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("xCam Bypass")
            .setContentText("Running in background")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onServiceBind(service: XposedService?) {
        isBound = true
        log("[+] Bound to XposedService")
    }

    override fun onServiceDied(service: XposedService?) {
        isBound = false
        log("[-] XposedService died")
    }

    fun getTargetPackage(): String? = currentTargetPackage

    fun getPipelineRoute(): String = PipelineTracker.getRouteSummary()

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        mediaEngine.stop()
        surfaceProvider.release()
        stopPositionPolling()
        log("[-] BypassService destroyed")
    }

    private fun log(msg: String) {
        Logger.i(this, msg)
    }
}
