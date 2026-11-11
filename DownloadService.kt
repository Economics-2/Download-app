package com.mhacademy.videodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

class DownloadService : Service() {

    companion object {
        @Volatile var ready = false
        @Volatile var token = ""
        @Volatile var activeJobs = 0
        @Volatile var initError: String? = null
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1

        /** ডাউনলোড শেষ বা ব্যর্থ হলে আলাদা নোটিফিকেশন দেয়। */
        fun notifyDone(ctx: Context, title: String, success: Boolean) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel("done", "ডাউনলোড সম্পন্ন", NotificationManager.IMPORTANCE_DEFAULT)
                )
                val pi = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = NotificationCompat.Builder(ctx, "done")
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(if (success) "ডাউনলোড সম্পূর্ণ" else "ডাউনলোড ব্যর্থ")
                    .setContentText(title)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
            } catch (e: Exception) {
                // নোটিফিকেশন না দেখালেও ডাউনলোড ঠিক থাকবে
            }
        }
    }

    private var server: LocalServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ডাউনলোড", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("প্রস্তুত হচ্ছে…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        Thread {
            try {
                YoutubeDL.init(applicationContext)
                FFmpeg.init(applicationContext)
                val t = UUID.randomUUID().toString().replace("-", "")
                val s = LocalServer(applicationContext, t) { n -> onActive(n) }
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                token = t
                ready = true
                updateNotification("প্রস্তুত")
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
            }
        }.start()
    }

    private fun onActive(n: Int) {
        activeJobs = n
        updateNotification(if (n > 0) "${n}টি ডাউনলোড চলছে…" else "প্রস্তুত")
        synchronized(this) {
            if (n > 0) {
                if (wakeLock == null) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videodownloader:dl")
                }
                if (wakeLock?.isHeld == false) wakeLock?.acquire(3 * 60 * 60 * 1000L)
            } else if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("ভিডিও ডাউনলোডার")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        ready = false
        server?.stop()
        synchronized(this) { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }
}
