package com.mhacademy.videodownloader

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ফোনের ভেতরে চলা লোকাল সার্ভার
class LocalServer(
    private val ctx: Context,
    private val token: String,
    private val onActiveChange: (Int) -> Unit
) : NanoHTTPD("127.0.0.1", PORT) {

    companion object {
        const val PORT = 8765
        const val MAX_PLAYLIST = 50
        const val MAX_HISTORY = 200
        private val LANG_RE = Regex("^[A-Za-z0-9_-]{2,15}$")
        private val ID_RE = Regex("^[0-9a-f]{10}$")
        private val ITEM_RE = Regex("""Downloading item (\d+) of (\d+)""")
        private val SPEED_RE = Regex("""at\s+(\S+/s)""")
    }

    private class Job {
        @Volatile var status = "downloading"
        @Volatile var percent = 0.0
        @Volatile var speed = ""
        @Volatile var eta = ""
        @Volatile var index = 1
        @Volatile var total = 1
        @Volatile var error = ""

        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("percent", Math.round(percent * 10) / 10.0)
            .put("speed", speed)
            .put("eta", eta)
            .put("index", index)
            .put("total", total)
            .put("error", error)
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val pool = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    private val lock = Any()

    // ---------- রাউটিং ----------
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                session.method == Method.GET && (uri == "/" || uri == "/index.html") -> page()
                !uri.startsWith("/api/") -> bad("পাওয়া যায়নি।", Response.Status.NOT_FOUND)
                session.headers["x-token"] != token -> bad("অনুমতি নেই।", Response.Status.FORBIDDEN)

                session.method == Method.POST && uri == "/api/info" ->
                    info(str(body(session), "url").trim())
                session.method == Method.POST && uri == "/api/download" ->
                    startDownload(body(session))
                session.method == Method.POST && uri == "/api/update" -> update()

                session.method == Method.GET && uri.startsWith("/api/status/") ->
                    status(uri.removePrefix("/api/status/"))
                session.method == Method.GET && uri == "/api/history" -> ok(history())
                session.method == Method.DELETE && uri == "/api/history" -> {
                    removeHistory(null)
                    ok(JSONObject().put("ok", true))
                }
                session.method == Method.DELETE && uri.startsWith("/api/history/") -> {
                    val id = uri.removePrefix("/api/history/")
                    if (!ID_RE.matches(id)) bad("ভুল আইডি।")
                    else {
                        removeHistory(id)
                        ok(JSONObject().put("ok", true))
                    }
                }
                else -> bad("পাওয়া যায়নি।", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            bad("সার্ভারে সমস্যা হয়েছে।", Response.Status.INTERNAL_ERROR)
        }
    }

    // ---------- ছোট সহায়ক ফাংশন ----------
    private fun ok(o: Any): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())
            .apply { addHeader("Cache-Control", "no-store") }

    private fun bad(msg: String, st: Response.IStatus = Response.Status.BAD_REQUEST): Response =
        newFixedLengthResponse(st, "application/json; charset=utf-8", JSONObject().put("error", msg).toString())

    private fun page(): Response {
        val html = ctx.assets.open("index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    /** JSON null-কেও খালি স্ট্রিং ধরে (org.json null-কে "null" লেখে, তাই এই সুরক্ষা)। */
    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)

    private fun validUrl(u: String) = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)

    private fun fmtEta(sec: Long): String = if (sec <= 0) "" else "%02d:%02d".format(sec / 60, sec % 60)

    // ---------- তথ্য আনা ----------
    private fun info(url: String): Response {
        if (!validUrl(url)) return bad("সঠিক লিংক দিন (http বা https দিয়ে শুরু)।")

        // ভিডিও লিংকে list= থাকলে ডিফল্টভাবে শুধু ওই ভিডিও ধরা হয়
        val videoInList = url.contains("list=") && (url.contains("v=") || url.contains("youtu.be/"))
        val req = YoutubeDLRequest(url)
        req.addOption("-J")
        req.addOption("--flat-playlist")
        req.addOption("--playlist-end", MAX_PLAYLIST)
        if (videoInList) req.addOption("--no-playlist")

        val d = try {
            JSONObject(YoutubeDL.execute(req, null, null).out)
        } catch (e: Exception) {
            return bad("তথ্য আনা যায়নি। লিংকটি ঠিক আছে কিনা এবং কনটেন্ট পাবলিক কিনা দেখুন।")
        }

        if (str(d, "_type") == "playlist") {
            val entries = d.optJSONArray("entries") ?: JSONArray()
            var total = d.optInt("playlist_count", 0)
            if (total <= 0) total = entries.length()
            var thumb = ""
            val first = entries.optJSONObject(0)
            if (first != null) {
                val th = first.optJSONArray("thumbnails")
                thumb = if (th != null && th.length() > 0) {
                    th.optJSONObject(th.length() - 1)?.let { str(it, "url") } ?: ""
                } else str(first, "thumbnail")
            }
            val subs = JSONArray()
            for (l in listOf("bn", "en", "hi")) subs.put(JSONObject().put("lang", l).put("auto", JSONObject.NULL))
            val uploader = str(d, "uploader").ifBlank { str(d, "channel") }
            return ok(
                JSONObject()
                    .put("kind", "playlist")
                    .put("title", str(d, "title").ifBlank { "প্লেলিস্ট" })
                    .put("uploader", uploader)
                    .put("count", total)
                    .put("limit", minOf(total, MAX_PLAYLIST))
                    .put("thumbnail", thumb)
                    .put("heights", JSONArray(listOf(1080, 720, 480, 360)))
                    .put("subs", subs)
                    .put("has_ffmpeg", true)
            )
        }

        val heights = mutableSetOf<Int>()
        val formats = d.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val h = f.optInt("height", 0)
                if (h > 0 && str(f, "vcodec") != "none") heights.add(h)
            }
        }

        val manualKeys = mutableListOf<String>()
        d.optJSONObject("subtitles")?.keys()?.forEach { if (it != "live_chat") manualKeys.add(it) }
        val auto = d.optJSONObject("automatic_captions")
        val subs = JSONArray()
        manualKeys.take(8).forEach { subs.put(JSONObject().put("lang", it).put("auto", false)) }
        for (l in listOf("bn", "en", "hi", "ar", "ur")) {
            if (auto != null && auto.has(l) && l !in manualKeys) {
                subs.put(JSONObject().put("lang", l).put("auto", true))
            }
        }

        return ok(
            JSONObject()
                .put("kind", "video")
                .put("title", str(d, "title").ifBlank { "শিরোনাম নেই" })
                .put("thumbnail", str(d, "thumbnail"))
                .put("duration", d.optInt("duration", 0))
                .put("uploader", str(d, "uploader"))
                .put("heights", JSONArray(heights.sortedDescending().take(6)))
                .put("subs", subs)
                .put("also_playlist", videoInList)
                .put("limit", MAX_PLAYLIST)
                .put("has_ffmpeg", true)
        )
    }

    // ---------- ডাউনলোড ----------
    private fun startDownload(b: JSONObject): Response {
        val url = str(b, "url").trim()
        if (!validUrl(url)) return bad("সঠিক লিংক দিন।")
        val kind = if (str(b, "kind") == "audio") "audio" else "video"
        val height = (if (b.isNull("height")) "" else b.optString("height")).toIntOrNull()
        val playlist = b.optBoolean("playlist", false)
        val title = str(b, "title").ifBlank { "download" }

        val subs = mutableListOf<Pair<String, Boolean?>>()
        val arr = b.optJSONArray("subs") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), 10)) {
            val o = arr.optJSONObject(i) ?: continue
            val lang = str(o, "lang")
            if (!LANG_RE.matches(lang)) continue
            val auto: Boolean? = if (o.isNull("auto")) null else o.optBoolean("auto")
            subs.add(lang to auto)
        }

        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        jobs[id] = Job()
        pool.execute { runDownload(id, url, kind, height, playlist, subs, title) }
        return ok(JSONObject().put("job", id))
    }

    private fun pickFormat(kind: String, height: Int?): String {
        if (kind == "audio") return "bestaudio/best"
        val h = if (height != null) "[height<=$height]" else ""
        return "bestvideo$h+bestaudio/best$h/best"
    }

    private fun runDownload(
        id: String,
        url: String,
        kind: String,
        height: Int?,
        playlist: Boolean,
        subs: List<Pair<String, Boolean?>>,
        title: String
    ) {
        val job = jobs[id] ?: return
        val dir = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }
        onActiveChange(active.incrementAndGet())
        try {
            val req = YoutubeDLRequest(url)
            req.addOption("--newline")
            req.addOption("--windows-filenames")
            req.addOption("--no-mtime")
            if (playlist) {
                req.addOption("--yes-playlist")
                req.addOption("--playlist-end", MAX_PLAYLIST)
                req.addOption("--ignore-errors") // একটি ভিডিও ব্যর্থ হলেও বাকিগুলো চলবে
            } else {
                req.addOption("--no-playlist")
            }

            req.addOption("-f", pickFormat(kind, height))
            if (kind == "audio") {
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "192K")
            } else {
                req.addOption("-S", "ext:mp4:m4a") // মোবাইলে চলার মতো MP4 পছন্দ
                req.addOption("--merge-output-format", "mp4")
            }

            if (subs.isNotEmpty()) {
                if (subs.any { it.second != true }) req.addOption("--write-subs")
                if (subs.any { it.second != false }) req.addOption("--write-auto-subs")
                req.addOption("--sub-langs", subs.joinToString(",") { it.first })
                req.addOption("--sub-format", "srt/vtt/best")
                req.addOption("--convert-subs", "srt")
            }

            val template = if (playlist) "%(playlist_index)03d_%(title).80s.%(ext)s" else "%(title).80s.%(ext)s"
            req.addOption("-o", File(dir, template).absolutePath)

            val cb: (Float, Long, String) -> Unit = { progress, eta, line ->
                ITEM_RE.find(line)?.let {
                    job.index = it.groupValues[1].toInt()
                    job.total = minOf(it.groupValues[2].toInt(), MAX_PLAYLIST)
                }
                SPEED_RE.find(line)?.let { job.speed = it.groupValues[1] }
                val pct = progress.coerceIn(0f, 100f).toDouble()
                val n = maxOf(job.total, 1)
                job.percent = ((job.index - 1) * 100 + pct) / n
                job.eta = fmtEta(eta)
            }

            try {
                YoutubeDL.execute(req, id, cb)
            } catch (e: Exception) {
                // প্লেলিস্টে কিছু ভিডিও নামলে সেগুলো রাখা হয়, কিছুই না নামলে ব্যর্থ ধরা হয়
                if (!playlist || dir.listFiles().isNullOrEmpty()) throw e
            }

            job.status = "processing"
            job.percent = 100.0
            val outFile = collectOutput(dir, title) ?: throw IllegalStateException("no files")
            saveToDownloads(outFile)

            addHistory(
                JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("url", url)
                    .put("kind", if (playlist) "playlist" else kind)
                    .put("quality", if (kind == "audio") "audio" else if (height != null) "${height}p" else "best")
                    .put("subs", JSONArray(subs.map { it.first }))
                    .put("name", outFile.name)
                    .put("size", outFile.length())
                    .put("time", isoNow())
                    .put("exists", true)
            )
            job.status = "done"
        } catch (e: Exception) {
            job.status = "error"
            job.error = "ডাউনলোড ব্যর্থ হয়েছে। অন্য কোয়ালিটি বেছে আবার চেষ্টা করুন।"
        } finally {
            dir.deleteRecursively()
            onActiveChange(active.decrementAndGet())
        }
    }

    /** একটি ফাইল হলে সেটাই, একাধিক হলে (প্লেলিস্ট/সাবটাইটেল) জিপ করে ফেরত দেয়। */
    private fun collectOutput(dir: File, title: String): File? {
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension !in setOf("part", "ytdl", "temp") }
            .sortedBy { it.name }
        if (files.isEmpty()) return null
        if (files.size == 1) return files[0]

        val safe = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]+"""), "_").trim(' ', '.').take(80).ifBlank { "download" }
        val zip = File(dir, "$safe.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.setLevel(0) // ভিডিও আগেই কমপ্রেসড
            for (f in files) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        files.forEach { it.delete() }
        return zip
    }

    /** ফাইলটি ফোনের Downloads/VideoDownloader ফোল্ডারে সেভ করে (MediaStore, কোনো স্টোরেজ পারমিশন লাগে না)। */
    private fun saveToDownloads(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDownloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("save failed")
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("save failed")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun status(id: String): Response {
        val job = jobs[id] ?: return bad("কাজটি পাওয়া যায়নি।", Response.Status.NOT_FOUND)
        return ok(job.toJson())
    }

    // ---------- yt-dlp আপডেট ----------
    private fun update(): Response = try {
        val st = YoutubeDL.updateYoutubeDL(ctx)
        ok(
            JSONObject()
                .put("status", st?.name ?: "UNKNOWN")
                .put("version", YoutubeDL.versionName(ctx) ?: "")
        )
    } catch (e: Exception) {
        bad("আপডেট করা যায়নি। ইন্টারনেট সংযোগ দেখুন।")
    }

    // ---------- ইতিহাস ----------
    private val histFile get() = File(ctx.filesDir, "history.json")

    private fun history(): JSONArray {
        synchronized(lock) {
            return try { JSONArray(histFile.readText()) } catch (e: Exception) { JSONArray() }
        }
    }

    private fun addHistory(item: JSONObject) {
        synchronized(lock) {
            val old = history()
            val arr = JSONArray().put(item)
            for (i in 0 until minOf(old.length(), MAX_HISTORY - 1)) arr.put(old.get(i))
            histFile.writeText(arr.toString())
        }
    }

    private fun removeHistory(id: String?) {
        synchronized(lock) {
            if (id == null) {
                histFile.writeText("[]")
                return
            }
            val old = history()
            val arr = JSONArray()
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (o.optString("id") != id) arr.put(o)
            }
            histFile.writeText(arr.toString())
        }
    }

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date())
    }
}
