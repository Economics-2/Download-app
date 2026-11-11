package com.mhacademy.videodownloader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var pendingShare: String? = null
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(Color.parseColor("#EDF3EE"))
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.startsWith("http://127.0.0.1")) {
                    pageLoaded = true
                    val p = pendingShare
                    pendingShare = null
                    if (p != null) deliver(p)
                }
            }
        }
        setContentView(web)
        showMessage("প্রস্তুত হচ্ছে… প্রথমবার একটু সময় লাগতে পারে।")

        pendingShare = extractLink(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        startForegroundService(Intent(this, DownloadService::class.java))
        waitForServer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = extractLink(intent) ?: return
        if (pageLoaded) deliver(link) else pendingShare = link
    }

    /** শেয়ার করা লেখা থেকে প্রথম লিংকটি বের করে। */
    private fun extractLink(i: Intent?): String? {
        if (i == null || i.action != Intent.ACTION_SEND) return null
        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return null
        return Regex("https?://\\S+").find(text)?.value
    }

    private fun deliver(link: String) {
        web.evaluateJavascript(
            "window.handleShared && window.handleShared(" + JSONObject.quote(link) + ")", null
        )
    }

    private fun waitForServer() {
        handler.postDelayed({
            when {
                DownloadService.ready -> {
                    val share = pendingShare
                    pendingShare = null
                    var u = "http://127.0.0.1:${LocalServer.PORT}/?t=${DownloadService.token}"
                    if (share != null) u += "&share=" + Uri.encode(share)
                    web.loadUrl(u)
                }
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
        if (isFinishing && DownloadService.activeJobs == 0) {
            stopService(Intent(this, DownloadService::class.java))
        }
        web.destroy()
        super.onDestroy()
    }
}
