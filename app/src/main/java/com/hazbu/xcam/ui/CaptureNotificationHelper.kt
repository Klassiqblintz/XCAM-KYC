package com.hazbu.xcam.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.app.NotificationCompat
import android.util.Log
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants

object CaptureNotificationHelper {

    fun createNotificationChannel(context: android.content.Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CAPTURE_NOTIFICATION_CHANNEL_ID,
                "xCam Capture Alerts",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows captured frame notifications"
            }
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun showCaptureNotification(
        context: android.content.Context,
        packageName: String,
        route: String,
        width: Int,
        height: Int,
        thumbnail: ByteArray?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CAPTURE_NOTIFICATION_CHANNEL_ID,
                "xCam Capture",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, Constants.CAPTURE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_xcam_notification)
            .setContentTitle("xCam Capture")
            .setContentText("Captured from $packageName\n$width x $height | $route")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pi)

        if (thumbnail != null && thumbnail.size < 128 * 1024) {
            try {
                val bm = android.graphics.BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size)
                builder.setLargeIcon(bm)
            } catch (_: Throwable) {}
        }

        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Constants.CAPTURE_NOTIFICATION_ID, builder.build())
    }
}
