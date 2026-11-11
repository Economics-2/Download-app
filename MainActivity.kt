package com.mhacademy.videodownloader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/** একটি WebView-তে লোকাল সার্ভারের UI দেখায়। */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(Color.parseColor("#EDF3EE"))
        setContentView(web)
        showMessage("প্রস্তুত হচ্ছে… প্রথমবার একটু সময় লাগতে পারে।")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        startForegroundService(Intent(this, DownloadService::class.java))
        waitForServer()
    }

    private fun waitForServer() {
        handler.postDelayed({
            when {
                DownloadService.ready ->
                    web.loadUrl("http://127.0.0.1:${LocalServer.PORT}/?t=${DownloadService.token}")
                DownloadService.initError != null ->
                    showMessage("চালু করা যায়নি: ${DownloadService.initError}")
                else -> waitForServer()
            }
        }, 400)
    }

    private fun showMessage(text: String) {
        val html = "<html><body style='font-family:sans-serif;padding:32px;color:#14261D;background:#EDF3EE'>" +
            "<h2>ভিডিও ডাউনলোডার</h2><p>$text</p></body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // কোনো ডাউনলোড চলতে থাকলে সার্ভিস চালু থাকবে
        if (isFinishing && DownloadService.activeJobs == 0) {
            stopService(Intent(this, DownloadService::class.java))
        }
        web.destroy()
        super.onDestroy()
    }
}
