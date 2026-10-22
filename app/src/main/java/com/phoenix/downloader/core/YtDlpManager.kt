package com.phoenix.downloader.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phoenix.downloader.model.FormatOption
import com.phoenix.downloader.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wrapper around yt-dlp via the youtubedl-android library.
 * Call [init] once at app start before use.
 */
object YtDlpManager {

    /**
     * User rule (standing): NEVER run an old yt-dlp — always update to the
     * latest stable at startup. The bundled 2025.11.12's JS-challenge solver
     * can no longer solve YouTube's current player ("found 0 n function
     * possibilities" -> formats missing / HTTP 403), so this stays ON.
     */
    private const val AUTO_UPDATE = true

    private var initialized = false
    private var updateChecked = false
    private var appContext: Context? = null

    /**
     * yt-dlp version BAKED INTO the APK as res/raw/ytdlp (the app module's raw
     * resource shadows the library's, so the library extracts THIS file on
     * first init). User order 2026-10-20: replace the bundled version
     * directly with the latest — no old version, ever. The runtime self-update
     * (AUTO_UPDATE) can still advance beyond this floor afterwards.
     */
    private const val BUNDLED_YTDLP_VERSION = "2026.10.21"

    /** Set when init() throws; the real reason fetches fail. */
    private var initError: Throwable? = null

    // ---- Format cache + pre-warm ----
    // On-device extraction takes seconds (Python start + JS challenge +
    // network round trips). Server-side extractors feel instant because
    // the list is ready before the tap. Our counter: start the fetch when
    // the user lands on a detail page or a browser watch page, and cache
    // the result, so the download dialog opens with the list already there.
    private data class CachedFormats(val result: FormatsResult, val atMs: Long)

    private const val FORMATS_CACHE_TTL_MS = 10 * 60 * 1000L
    private val formatsCache = object : LinkedHashMap<String, CachedFormats>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedFormats>
        ): Boolean = size > 8
    }
    private val formatsInFlight = HashSet<String>()
    private val managerScope =
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    // ---- Raw extraction JSON cache (playback reuse) ----
    // Every full --dump-json extraction (fetchVideoInfo, fetchFormatsOnce)
    // already contains direct stream URLs for every format. Stash the raw
    // JSON so resolveStreamUrls can pick a playable pair from it instead of
    // paying for a second extraction — on the detail page the pre-warm and
    // the player used to run two extractions side by side.
    private data class CachedJson(val json: org.json.JSONObject, val atMs: Long)

    private val streamJsonCache = object : LinkedHashMap<String, CachedJson>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedJson>
        ): Boolean = size > 8
    }

    private fun stashStreamJson(url: String, json: org.json.JSONObject) {
        synchronized(streamJsonCache) {
            streamJsonCache[url] = CachedJson(json, System.currentTimeMillis())
        }
    }

    private fun cachedStreamJson(url: String): org.json.JSONObject? =
        synchronized(streamJsonCache) {
            val hit = streamJsonCache[url] ?: return null
            if (System.currentTimeMillis() - hit.atMs > FORMATS_CACHE_TTL_MS) {
                streamJsonCache.remove(url)
                null
            } else {
                hit.json
            }
        }

    private fun cachedFormats(url: String): FormatsResult? = synchronized(formatsCache) {
        val hit = formatsCache[url] ?: return null
        if (System.currentTimeMillis() - hit.atMs > FORMATS_CACHE_TTL_MS) {
            formatsCache.remove(url)
            null
        } else {
            hit.result
        }
    }

    /**
     * Fire-and-forget: fetch formats into the cache in the background
     * (fast-first attempt chain inside fetchFormats), so the download
     * dialog usually opens with the list already here. NOTE: the
     * /player-metadata advisory list that used to run here first was
     * REMOVED 2026-10-22 — anonymous /player returns no streamingData
     * for real videos (probed: UNPLAYABLE / LOGIN_REQUIRED), so it
     * silently never produced a list.
     */
    fun prewarmFormats(url: String) {
        if (url.isEmpty() || cachedFormats(url) != null) return
        val started = synchronized(formatsInFlight) { formatsInFlight.add(url) }
        if (!started) return
        managerScope.launch {
            try {
                fetchFormats(url)
            } finally {
                synchronized(formatsInFlight) { formatsInFlight.remove(url) }
            }
        }
    }

    fun init(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            // One-time purge per BUNDLED_YTDLP_VERSION change: installs made
            // from older APKs already extracted the OLD yt-dlp into
            // noBackupFilesDir, and the library only extracts res/raw/ytdlp
            // when the binary is missing. Delete the extracted dir so the
            // baked-in 2026.10.21 is extracted on this init. Runs BEFORE
            // YoutubeDL.init; the marker makes later launches (and any newer
            // self-updated version) untouched.
            try {
                val prefs = appContext!!.getSharedPreferences("phoenix_ytdlp", Context.MODE_PRIVATE)
                if (prefs.getString("bundled_ytdlp_version", null) != BUNDLED_YTDLP_VERSION) {
                    val dir = java.io.File(
                        appContext!!.noBackupFilesDir, "youtubedl-android/yt-dlp"
                    )
                    if (dir.exists()) dir.deleteRecursively()
                    prefs.edit()
                        .putString("bundled_ytdlp_version", BUNDLED_YTDLP_VERSION)
                        .apply()
                    logNote(
                        "YtDlpManager.bundled",
                        "purged extracted yt-dlp; baked-in version $BUNDLED_YTDLP_VERSION will be extracted"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("YtDlpManager", "bundled yt-dlp purge failed", e)
            }
            YoutubeDL.getInstance().init(appContext!!)
            // Initialize FFmpeg for audio/video merging (4K downloads)
            try {
                com.yausername.ffmpeg.FFmpeg.getInstance().init(appContext!!)
            } catch (e: Exception) {
                // FFmpeg init optional, log but don't fail
                android.util.Log.w("YtDlpManager", "FFmpeg init failed", e)
            }
            // Initialize Aria2c for multi-connection downloads
            try {
                com.yausername.aria2c.Aria2c.getInstance().init(appContext!!)
            } catch (e: Exception) {
                android.util.Log.w("YtDlpManager", "Aria2c init failed", e)
            }
            initialized = true
            updateChecked = true
            initError = null
            logNote("YtDlpManager.version", "yt-dlp version after init: ${getYtDlpVersion()}")
            if (AUTO_UPDATE) {
                CoroutineScope(Dispatchers.IO).launch {
                    // Update FIRST, then diagnose, so the verbose log shows the
                    // version that will actually serve fetches/downloads.
                    ensureUpdated()
                    runVerboseDiagnostic()
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    runVerboseDiagnostic()
                }
            }
        } catch (e: Exception) {
            initError = e
            logFailure("YtDlpManager.init", e)
        }
    }

    fun isInitialized(): Boolean = initialized

    /**
     * Runs yt-dlp --verbose once and logs the debug header lines that state the
     * Python version and the JS runtimes in use, plus any qjs/python files found
     * in the native lib dir. Pure diagnostics — result lands in crash.log.
     */
    private suspend fun runVerboseDiagnostic() {
        try {
            val ctx = appContext ?: return
            val nativeDir = ctx.applicationInfo.nativeLibraryDir ?: ""
            val libs = try {
                java.io.File(nativeDir).listFiles()
                    ?.map { it.name }
                    ?.filter { it.contains("qjs") || it.contains("python") }
                    ?.joinToString(", ") ?: "none"
            } catch (e: Exception) {
                "list failed: ${e.message}"
            }
            logNote("YtDlpDiag.libs", "nativeLibDir=$nativeDir libs=[$libs]")

            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            req.addOption("--verbose")
            req.addOption("--simulate")
            req.addOption("--no-download")
            var text = ""
            try {
                val resp = YoutubeDL.getInstance().execute(req)
                text = (resp.out ?: "") + "\n" + (resp.err ?: "")
            } catch (e: Exception) {
                // yt-dlp writes the debug header to stderr before failing;
                // the library folds stderr into the exception message.
                text = e.message ?: ""
            }
            val interesting = text.lineSequence()
                .map { it.trim() }
                .filter {
                    it.contains("Python", ignoreCase = true) ||
                        it.contains("JS runtime", ignoreCase = true) ||
                        it.contains("js-runtimes", ignoreCase = true) ||
                        it.contains("quickjs", ignoreCase = true) ||
                        it.contains("yt-dlp version", ignoreCase = true)
                }
                .take(12)
                .joinToString(" | ")
            logNote(
                "YtDlpDiag.verbose",
                if (interesting.isNotEmpty()) interesting else "no debug lines captured; raw head: ${text.take(400)}"
            )
        } catch (e: Exception) {
            logFailure("YtDlpDiag", e)
        }
    }

    fun getInitError(): Throwable? = initError

    fun retryInit(context: Context) {
        initError = null
        initialized = false
        updateChecked = false
        init(context)
    }

    fun notReadyReason(): String {
        val err = initError ?: return "yt-dlp not initialized yet, please wait..."
        val chain = generateSequence<Throwable>(err) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        return "yt-dlp init failed: $chain"
    }

    fun isReady(): Boolean = initialized && updateChecked

    fun markUpdateChecked() { updateChecked = true }

    /** Full error goes to crash.log (long-press the language button to copy it). */
    private fun logFailure(tag: String, e: Throwable) {
        appContext?.let { ctx ->
            try {
                CrashLogger.logNonFatal(ctx, tag, e)
            } catch (_: Exception) { }
        }
    }

    private fun logNote(tag: String, message: String) {
        logFailure(tag, Exception(message))
    }

    /** Common options. No forced player_client, no custom user-agent, TLS checks on. */
    private fun baseRequest(url: String): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--socket-timeout", "30")
        request.addOption("--extractor-retries", "3")
        // A watch URL copied from a Mix page can carry &list= — the app
        // always means the single video (Claude review point, 2026-10-22).
        request.addOption("--no-playlist")
        appContext?.let { ctx ->
            CookieHelper.getCookieFilePath(ctx)?.let { cookiePath ->
                request.addOption("--cookies", cookiePath)
            }
            // Persistent cache dir: solved signature/nsig results are
            // disk-cached and reused across runs. NOTE (verified in the
            // baked yt-dlp source): the player JS itself is only cached
            // per-process in memory, so this does NOT stop its
            // re-download — the /player-metadata fast list exists for
            // that. This is a modest, free saving on repeat extractions.
            try {
                request.addOption(
                    "--cache-dir",
                    java.io.File(ctx.noBackupFilesDir, "ytdlp-cache").absolutePath
                )
            } catch (_: Exception) {
            }
        }
        return request
    }

    /** Step 1: resolve a video URL to its info (thumbnail, title, duration). */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            val request = baseRequest(url)
            // Fast skips (2026-10-22): info needs no watch-page HTML
            // or manifests either — same first-attempt flags as the
            // format fetch. (The formats seed below parses whatever
            // this dump carries; if a video ever needs the full path,
            // fetchFormats runs its own chain for the dialog.)
            request.addOption(
                "--extractor-args",
                "youtube:player_skip=webpage,configs,initial_data;skip=hls,dash,translated_subs"
            )
            request.addOption("--dump-json")
            request.addOption("--no-download")
            val response = YoutubeDL.getInstance().execute(request)
            val json = org.json.JSONObject(response.out)
            stashStreamJson(url, json)

            // Speed (2026-10-21): this dump already carries the full
            // formats array — seed the formats cache so the download
            // dialog does not pay for a second extraction. The request
            // is identical to the default-client formats attempt, so
            // the seeded list is exactly what fetchFormats returns.
            try {
                val seeded = parseFormatsJson(json, null, response.err ?: "", url)
                if (seeded.video.isNotEmpty() || seeded.music.isNotEmpty()) {
                    synchronized(formatsCache) {
                        formatsCache[url] =
                            CachedFormats(seeded, System.currentTimeMillis())
                    }
                    logNote(
                        "fetchVideoInfo.seed",
                        "formats cached url=$url video=${seeded.video.size} audio=${seeded.music.size}"
                    )
                }
            } catch (e: Exception) {
                logNote("fetchVideoInfo.seed", "seed failed url=$url: ${e.message}")
            }

            Result.success(
                VideoInfo(
                    url = url,
                    title = json.optString("title", ""),
                    uploader = json.optString("uploader", ""),
                    durationSec = json.optLong("duration", 0),
                    viewCount = formatViews(json.optLong("view_count", 0)),
                    thumbnailUrl = json.optString("thumbnail", "").ifEmpty { null }
                )
            )
        } catch (e: Exception) {
            logFailure("fetchVideoInfo", e)
            Result.failure(e)
        }
    }

    /**
     * Step 2: list downloadable formats, split into music and video.
     *
     * Speed: ONE extraction with the default client normally suffices
     * (yt-dlp 2026.10.21's solver is current). The tv / web_safari clients
     * are only tried if the default attempt throws or returns nothing —
     * the old always-try-everyone chain cost up to 3x the wait.
     *
     * The video list is collapsed to ONE format per quality tier
     * (360/480/720/1080/1440/4K...): the best at each height, MP4 sources
     * preferred (user rule 2026-10-20).
     */
    suspend fun fetchFormats(url: String): Result<FormatsResult> = withContext(Dispatchers.IO) {
        cachedFormats(url)?.let {
            logNote("fetchFormats.cache", "hit url=$url")
            return@withContext Result.success(it)
        }
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            // Speed (2026-10-21): do not block the dialog on the yt-dlp
            // self-update. It already runs in the background from init;
            // firing it here too (mutex-guarded, once per session)
            // keeps the trigger without making a session's first fetch
            // wait on a slow network. The baked-in yt-dlp is the latest
            // stable, so proceeding at once never runs an old version.
            managerScope.launch { ensureUpdated() }
            // Attempt order (fast-first, 2026-10-22): the first attempt
            // skips the watch-page HTML (~1MB), client configs and the
            // DASH/HLS manifests — the bytes dominate on a slow link.
            // It is only accepted with a plausible coverage (maxHeight
            // >= 720); anything less falls through to the proven chain
            // (default, then tv / web_safari on failure).
            val attempts = listOf(null to true, null to false, "tv" to false, "web_safari" to false)
            var lastError: Exception? = null
            for ((client, fast) in attempts) {
                try {
                    val attempt = fetchFormatsOnce(url, client, fast)
                    val usable = attempt.video.isNotEmpty() || attempt.music.isNotEmpty()
                    val coverageOk = !fast || attempt.maxHeight >= 720
                    if (usable && coverageOk) {
                        synchronized(formatsCache) {
                            formatsCache[url] =
                                CachedFormats(attempt, System.currentTimeMillis())
                        }
                        return@withContext Result.success(attempt)
                    }
                    lastError = Exception(
                        "client=${client ?: "default"} fast=$fast returned no formats"
                    )
                } catch (e: Exception) {
                    lastError = e
                    logNote(
                        "fetchFormats.attempt",
                        "client=${client ?: "default"} fast=$fast failed: ${e.message}"
                    )
                }
            }
            Result.failure(lastError ?: Exception("No formats found"))
        } catch (e: Exception) {
            logFailure("fetchFormats", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchFormatsOnce(
        url: String,
        client: String?,
        fast: Boolean = false
    ): FormatsResult =
        withContext(Dispatchers.IO) {
            val request = baseRequest(url)
            val args = StringBuilder("youtube:")
            var hasArgs = false
            fun arg(s: String) {
                if (hasArgs) args.append(';')
                args.append(s)
                hasArgs = true
            }
            if (client != null) arg("player_client=$client")
            if (fast) {
                arg("player_skip=webpage,configs,initial_data")
                arg("skip=hls,dash,translated_subs")
            }
            if (hasArgs) request.addOption("--extractor-args", args.toString())
            request.addOption("--dump-json")
            request.addOption("--no-download")
            val response = YoutubeDL.getInstance().execute(request)
            val json = org.json.JSONObject(response.out)
            stashStreamJson(url, json)
            parseFormatsJson(json, client, response.err ?: "", url)
        }

    /**
     * Parse one --dump-json extraction's formats array into the
     * dialog's music/video lists. Shared by fetchFormatsOnce and by
     * fetchVideoInfo's cache seeding (speed fix 2026-10-21: one
     * extraction serves info + dialog instead of two).
     */
    private fun parseFormatsJson(
        json: org.json.JSONObject,
        client: String?,
        errText: String,
        url: String
    ): FormatsResult {
            val music = mutableListOf<FormatOption>()
            // ONE video format per quality tier (user rule): the best at each
            // height, MP4 sources preferred, then the largest file.
            val bestByHeight = mutableMapOf<Int, FormatOption>()
            val bestSizeByHeight = mutableMapOf<Int, Long>()
            var maxHeight = 0
            val arr = json.optJSONArray("formats") ?: org.json.JSONArray()

            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.has("url")) continue

                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                // Skip storyboards / thumbnails (no video and no audio)
                if (vcodec == "none" && acodec == "none") continue

                val height = f.optInt("height", 0)
                val isAudioOnly = vcodec == "none" && acodec != "none"
                if (!isAudioOnly && height > maxHeight) maxHeight = height

                val label = if (isAudioOnly) {
                    val abr = f.optInt("abr", 0)
                    if (abr > 0) "${abr}K" else "Audio"
                } else {
                    if (height > 0) "${height}P" + if (height >= 720) " HD" else ""
                    else "Video"
                }

                val sizeBytes = f.optLong("filesize", 0).takeIf { it > 0 }
                    ?: f.optLong("filesize_approx", 0)
                val opt = FormatOption(
                    id = f.optString("format_id", ""),
                    label = label,
                    format = f.optString("ext", "").uppercase(),
                    sizeLabel = formatBytes(sizeBytes),
                    isAudio = isAudioOnly
                )
                if (isAudioOnly) {
                    music.add(opt)
                } else {
                    val cur = bestByHeight[height]
                    val better = when {
                        cur == null -> true
                        opt.format == "MP4" && cur.format != "MP4" -> true
                        opt.format != "MP4" && cur.format == "MP4" -> false
                        else -> sizeBytes > (bestSizeByHeight[height] ?: -1L)
                    }
                    if (better) {
                        bestByHeight[height] = opt
                        bestSizeByHeight[height] = sizeBytes
                    }
                }
            }

            val sortedVideo = bestByHeight.values.sortedBy {
                it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
            }
            val poNote = when {
                errText.contains("PO Token", ignoreCase = true) -> " PO-TOKEN-warning"
                errText.contains("skipped", ignoreCase = true) -> " skipped-formats"
                else -> ""
            }
            // Diagnostic: per-attempt proof of what each client returned.
            logNote(
                "fetchFormats.list",
                "client=${client ?: "default"} url=$url maxHeight=$maxHeight " +
                    "video=${sortedVideo.size} [" +
                    sortedVideo.joinToString(", ") { "${it.label}/${it.format}/${it.id}" } +
                    "] audio=${music.size}$poNote"
            )
            return FormatsResult(
                music = music,
                video = sortedVideo,
                maxHeight = maxHeight,
                clientUsed = client
            )
    }

    /**
     * Resolve ONE direct playable URL (video + audio together) for ExoPlayer.
     * "bv*+ba" would return two URLs and the player would get video only.
     */
    /** Direct playable stream URLs for the preview player. */
    data class StreamUrls(val videoUrl: String, val audioUrl: String?)

    private fun videoScore(f: org.json.JSONObject): Long =
        f.optInt("height", 0).toLong() * 1_000_000L +
            f.optDouble("tbr", 0.0).toLong()

    private fun audioScore(f: org.json.JSONObject): Long {
        val abr = f.optDouble("abr", 0.0)
        val rate = if (abr > 0) abr else f.optDouble("tbr", 0.0)
        return rate.toLong() * 1_000L + f.optLong("filesize", 0) / 1024
    }

    /**
     * Pick a playable (video, audio) pair out of a full extraction's
     * formats array — the same selection the live path's -f selector
     * makes (best video <=720p + best audio), without a new extraction.
     * Returns null when the array carries no directly usable URLs.
     */
    private fun streamUrlsFromJson(json: org.json.JSONObject): StreamUrls? {
        val arr = json.optJSONArray("formats") ?: return null
        var bestVideoLe720: org.json.JSONObject? = null
        var bestVideoAny: org.json.JSONObject? = null
        var bestAudio: org.json.JSONObject? = null
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            if (f.optString("url", "").isEmpty()) continue
            val vc = f.optString("vcodec", "none")
            val ac = f.optString("acodec", "none")
            if (vc == "none" && ac == "none") continue
            if (vc != "none") {
                val h = f.optInt("height", 0)
                if (h in 1..720 &&
                    (bestVideoLe720 == null || videoScore(f) > videoScore(bestVideoLe720!!))
                ) {
                    bestVideoLe720 = f
                }
                if (bestVideoAny == null || videoScore(f) > videoScore(bestVideoAny!!)) {
                    bestVideoAny = f
                }
            } else if (ac != "none") {
                if (bestAudio == null || audioScore(f) > audioScore(bestAudio!!)) {
                    bestAudio = f
                }
            }
        }
        val video = bestVideoLe720 ?: bestVideoAny ?: return null
        val videoUrl = video.optString("url", "")
        if (videoUrl.isEmpty()) return null
        val muxed = video.optString("acodec", "none") != "none"
        val audioUrl = if (muxed) {
            null
        } else {
            bestAudio?.optString("url", "")?.ifEmpty { null }
        }
        return StreamUrls(videoUrl, audioUrl)
    }

    /**
     * Resolve playable stream URLs (<=720p for a fast preview start).
     *
     * The old single-URL selector ("b[ext=mp4]") died in the field with
     * "Requested format is not available": modern YouTube serves no muxed
     * file beyond 360p (and often not even that). So resolve a DASH pair
     * and let the player merge video+audio (MergingMediaSource). The JSON
     * dump's requested_formats say exactly which URL is video vs audio.
     */
    suspend fun resolveStreamUrls(url: String): Result<StreamUrls> = withContext(Dispatchers.IO) {
        try {
            if (!isReady()) return@withContext Result.failure(Exception(notReadyReason()))
            // Speed (2026-10-21): never make playback wait on the yt-dlp
            // self-update — fire it in the background exactly like
            // fetchFormats does. The baked-in yt-dlp is the latest stable,
            // so playing at once never runs an old version.
            managerScope.launch { ensureUpdated() }
            // Speed (2026-10-21): reuse a full extraction's JSON when one
            // is already cached for this URL (detail-page pre-warm,
            // browser info fetch, dialog fetch) instead of paying for a
            // second extraction. If that fetch is still in flight, JOIN
            // it — wait while it runs (up to 30s) instead of starting a
            // duplicate. Field log 2026-10-22: the old 12s cap expired
            // before a 17s extraction landed, so resolve ran a second
            // full extraction and playback paid for both.
            var waitedMs = 0L
            while (true) {
                val cached = cachedStreamJson(url)
                if (cached != null) {
                    val fromCache = try {
                        streamUrlsFromJson(cached)
                    } catch (e: Exception) {
                        null
                    }
                    if (fromCache != null) {
                        logNote(
                            "resolveStreamUrls.cache",
                            "hit url=$url waitedMs=$waitedMs audio=${if (fromCache.audioUrl != null) "yes" else "no"}"
                        )
                        return@withContext Result.success(fromCache)
                    }
                }
                val inFlight = synchronized(formatsInFlight) { url in formatsInFlight }
                if (!inFlight || waitedMs >= 30_000L) break
                delay(500)
                waitedMs += 500
            }
            var lastError: Exception? = null
            for (client in listOf<String?>(null, "tv")) {
                try {
                    val request = baseRequest(url)
                    if (client != null) {
                        request.addOption("--extractor-args", "youtube:player_client=$client")
                    }
                    request.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b")
                    request.addOption("--dump-json")
                    request.addOption("--no-download")
                    val response = YoutubeDL.getInstance().execute(request)
                    val json = org.json.JSONObject(response.out)
                    stashStreamJson(url, json)
                    val requested = json.optJSONArray("requested_formats")
                    var video: String? = null
                    var audio: String? = null
                    if (requested != null && requested.length() > 0) {
                        for (i in 0 until requested.length()) {
                            val f = requested.getJSONObject(i)
                            val u = f.optString("url", "")
                            if (u.isEmpty()) continue
                            val vc = f.optString("vcodec", "none")
                            val ac = f.optString("acodec", "none")
                            if (vc != "none" && video == null) video = u
                            else if (vc == "none" && ac != "none" && audio == null) audio = u
                        }
                    }
                    if (video == null) {
                        // Single muxed format selected
                        video = json.optString("url", "").ifEmpty { null }
                    }
                    if (video != null) {
                        logNote(
                            "resolveStreamUrls",
                            "client=${client ?: "default"} video=yes audio=${if (audio != null) "yes" else "no"}"
                        )
                        return@withContext Result.success(StreamUrls(video, audio))
                    }
                    lastError = Exception("client=${client ?: "default"}: no stream urls")
                } catch (e: Exception) {
                    lastError = e
                    logNote("resolveStreamUrls.attempt", "client=${client ?: "default"}: ${e.message}")
                }
            }
            Result.failure(lastError ?: Exception("No playable stream found"))
        } catch (e: Exception) {
            logFailure("resolveStreamUrls", e)
            Result.failure(e)
        }
    }

    /** Update yt-dlp from the STABLE channel. Returns true if an update was installed. */
    private val updateMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var updateDone = false

    private const val UPDATE_CHANNEL_ID = "phoenix_update"
    private const val UPDATE_NOTIF_ID = 4401
    private const val PREF_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
    private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * Tell the user a yt-dlp update download is really in progress
     * (user request 2026-10-22). Shown ONLY when a newer version is
     * actually being downloaded — never for the background check —
     * and removed as soon as the download finishes.
     */
    private fun showUpdateNotification(version: String) {
        val ctx = appContext ?: return
        try {
            val nm =
                ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        UPDATE_CHANNEL_ID,
                        "App updates",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val notif = NotificationCompat.Builder(ctx, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Updating yt-dlp…")
                .setContentText("Downloading version $version — one moment.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(UPDATE_NOTIF_ID, notif)
        } catch (e: Exception) {
            android.util.Log.w("YtDlpManager", "update notification failed", e)
        }
    }

    private fun hideUpdateNotification() {
        val ctx = appContext ?: return
        try {
            val nm =
                ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(UPDATE_NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    /**
     * Honest yt-dlp update flow (user verdict 2026-10-22: the old flow
     * showed "Updating yt-dlp…" on nearly every launch while merely
     * CHECKING, and never persisted that it had checked — it looked
     * like it never really updated). Now: at most one check per 24h,
     * persisted across restarts. The check is a tiny GitHub release
     * lookup compared against the installed version; when they match,
     * nothing else happens and NO notification is shown. The
     * notification appears only when a newer version really is about
     * to download, and it names that version. Serialized; called from
     * init (background), fired (not awaited) at the top of fetch /
     * resolve, and awaited by downloads — with the 24h gate that wait
     * is normally instant.
     */
    suspend fun ensureUpdated() {
        if (updateDone) return
        updateMutex.withLock {
            if (updateDone) return
            val prefs = try {
                appContext?.getSharedPreferences("phoenix_ytdlp", Context.MODE_PRIVATE)
            } catch (e: Exception) {
                null
            }
            val lastCheck = prefs?.getLong(PREF_LAST_UPDATE_CHECK_MS, 0L) ?: 0L
            if (System.currentTimeMillis() - lastCheck < UPDATE_CHECK_INTERVAL_MS) {
                logNote(
                    "YtDlpManager.update",
                    "skipped — checked <24h ago; version: ${getYtDlpVersion()}"
                )
                updateDone = true
                return
            }
            val latest = fetchLatestYtDlpTag()
            if (latest == null) {
                // GitHub unreachable: stay silent, do not stamp the
                // gate — the next session retries the check.
                logNote("YtDlpManager.update", "check failed — latest release unreachable")
                return
            }
            try {
                prefs?.edit()
                    ?.putLong(PREF_LAST_UPDATE_CHECK_MS, System.currentTimeMillis())
                    ?.apply()
            } catch (_: Exception) {
            }
            val installed = installedVersionTag()
            if (installed != null && compareVersionTags(installed, latest) >= 0) {
                logNote(
                    "YtDlpManager.update",
                    "up to date: installed=$installed latest=$latest"
                )
                updateDone = true
                return
            }
            // A real update exists (or the installed version is
            // unknown): only NOW does the notification appear, and it
            // means a download is really starting.
            logNote(
                "YtDlpManager.update",
                "update available: installed=$installed latest=$latest — downloading"
            )
            showUpdateNotification(latest)
            try {
                val r = kotlinx.coroutines.withTimeoutOrNull(120_000) { checkForUpdates() }
                if (r != null) {
                    logNote(
                        "YtDlpManager.update",
                        "installed=${r.getOrNull()} error=${r.exceptionOrNull()?.message} " +
                            "version now: ${getYtDlpVersion()}"
                    )
                    updateDone = true
                } else {
                    logNote("YtDlpManager.update", "update timed out (120s) — proceeding with installed version")
                }
            } finally {
                hideUpdateNotification()
            }
        }
    }

    /** Installed yt-dlp version as a release tag ("2026.10.21"), or null. */
    private fun installedVersionTag(): String? {
        val raw = getYtDlpVersion().trim()
        return raw.split(" ").firstOrNull {
            it.matches(Regex("""\d{4}\.\d{1,2}\.\d{1,2}.*"""))
        }
    }

    /** Numeric compare of date-based version tags: negative/0/positive. */
    private fun compareVersionTags(a: String, b: String): Int {
        val pa = a.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** Latest stable yt-dlp tag from GitHub (one small API call), or null. */
    private fun fetchLatestYtDlpTag(): String? {
        return try {
            val conn = java.net.URL(
                "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
            ).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "PhoenixDownloader")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val tag = org.json.JSONObject(
                conn.inputStream.bufferedReader().readText()
            ).optString("tag_name", "").trim()
            tag.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun checkForUpdates(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ctx = appContext ?: return@withContext Result.failure(Exception("Not initialized"))
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val status = YoutubeDL.getInstance()
                        .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    if (status == YoutubeDL.UpdateStatus.DONE ||
                        status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
                    ) {
                        return@withContext Result.success(status == YoutubeDL.UpdateStatus.DONE)
                    }
                    lastError = Exception("Update status: $status, attempt $attempt")
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 3) delay(2000)
                }
            }
            Result.failure(lastError ?: Exception("Update failed after 3 attempts"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Current yt-dlp version string for Settings -> About */
    fun getYtDlpVersion(): String {
        return try {
            val ctx = appContext ?: return "unknown"
            // versionName holds the release name set by the self-updater
            // (null until the first successful update).
            YoutubeDL.getInstance().versionName(ctx)
                ?: YoutubeDL.getInstance().version(ctx)
                ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun formatViews(count: Long): String = when {
        count >= 1_000_000 -> "${count / 1_000_000}M"
        count >= 1_000 -> "${count / 1_000}K"
        else -> count.toString()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_073_741_824 -> "%.1fGB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    data class FormatsResult(
        val music: List<FormatOption>,
        val video: List<FormatOption>,
        val maxHeight: Int = 0,
        /** yt-dlp player_client that produced this list (null = library default). */
        val clientUsed: String? = null
    )
}
