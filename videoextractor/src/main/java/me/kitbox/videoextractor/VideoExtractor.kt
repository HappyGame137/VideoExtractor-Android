package me.kitbox.videoextractor

import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class Progress(val label: String, val bytes: Long = 0, val total: Long = -1)
internal data class SavedVideo(val uri: android.net.Uri, val name: String, val bytes: Long)

internal class VideoExtractor(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()

    suspend fun extract(link: VideoLink, update: (Progress) -> Unit): SavedVideo = withContext(Dispatchers.IO) {
        val folder = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "video-").toFile()
        try {
            update(Progress("正在解析${link.source.title}链接"))
            val target = File(folder, "video.mp4")
            val title = when (link.source) {
                Source.BILI -> bili(link.url, target, update)
                Source.DOUYIN, Source.XHS -> {
                    val page = resolvePageVideo(link)
                    downloadFirst(page.urls, link.source, target, "正在下载视频", update, "video/")
                    page.title
                }
                Source.DIRECT -> {
                    downloadFirst(listOf(link.url.toString()), link.source, target, "正在下载视频", update, "video/")
                    link.url.pathSegments.last().substringBeforeLast('.', "视频")
                }
            }
            update(Progress("正在保存到相册"))
            saveVideo(context.contentResolver, target, title)
        } finally {
            folder.deleteRecursively()
        }
    }

    private suspend fun resolvePageVideo(link: VideoLink): PageVideo {
        val source = link.source
        val original = link.url
        if (source == Source.DOUYIN && original.encodedPath.contains("/note/")) {
            throw VideoError("这是图文作品，没有可提取的视频。")
        }
        val expected = if (source == Source.DOUYIN) original.douyinId() else original.xhsId()
        var browserUrl = if (source == Source.DOUYIN && expected != null)
            "https://www.douyin.com/video/$expected".toHttpUrl() else original
        try {
            val page = readPage(original, source)
            val id = if (source == Source.DOUYIN) page.url.douyinId() else page.url.xhsId()
            if (expected != null && id != null && id != expected) throw WrongVideo()
            if (source == Source.DOUYIN) {
                val aweme = expected ?: id
                if (aweme != null) {
                    browserUrl = "https://www.douyin.com/video/$aweme".toHttpUrl()
                    douyinVideo(page.body, aweme)?.let { return it }
                    val mobile = "https://www.iesdouyin.com/share/video/$aweme/".toHttpUrl()
                    if (mobile != page.url) {
                        val detail = readPage(mobile, source)
                        if (detail.url.douyinId()?.let { it != aweme } == true) throw WrongVideo()
                        douyinVideo(detail.body, aweme)?.let { return it }
                    }
                }
            } else if (id != null) {
                browserUrl = page.url
                xhsVideo(page.body, id)?.let { return it }
            }
        } catch (e: IOException) {
            if (e is VideoError || e is WrongVideo) throw e
            // A normal browser may receive a different representation of the same public page.
        }
        return readInBrowser(context, source, browserUrl, expected)
            ?: throw VideoError("平台未提供可下载的视频。请从原作品重新复制完整分享链接；需要登录或验证的作品暂不支持。")
    }

    private suspend fun bili(url: HttpUrl, target: File, update: (Progress) -> Unit): String {
        val canonical = if (url.onDomain("b23.tv")) {
            request(url, Source.BILI, page = true) { it.request.url }
        } else url
        val id = Regex("""(?i)^/(?:video/)?(BV[0-9A-Za-z]{10}|av[0-9]+)(?:/|$)""")
            .find(canonical.encodedPath)?.groupValues?.get(1) ?: throw VideoError("请粘贴 B 站普通视频的分享链接或 BV 号。")
        val part = canonical.queryParameter("p")?.let {
            it.toIntOrNull()?.takeIf { page -> page > 0 } ?: throw VideoError("B 站分 P 编号无效。")
        } ?: 1
        val arg = if (id.startsWith("BV", true)) "bvid=BV${id.drop(2)}" else "aid=${id.drop(2)}"
        var embedded: Page? = null
        val view = networkOrNull { biliApi("/x/web-interface/view?$arg") } ?: run {
            embedded = readPage(canonical, Source.BILI)
            val state = assignedObject(embedded.body, "__INITIAL_STATE__")
            state?.get("videoData").obj() ?: throw VideoError("B 站未返回视频信息，视频可能已删除或限制访问。")
        }
        val bvid = view.text("bvid")?.takeIf { it.matches(Regex("BV[0-9A-Za-z]{10}")) }
            ?: throw VideoError("B 站未返回有效的视频编号。")
        val identityMatches = if (arg.startsWith("bvid=")) bvid == arg.substringAfter('=') else
            view.number("aid") == id.drop(2).toLongOrNull()
        if (!identityMatches) throw WrongVideo()
        val pages = view["pages"].items().mapNotNull { it.obj() }
        val chosen = pages.firstOrNull { it.number("page") == part.toLong() }
            ?: view.takeIf { pages.isEmpty() && part == 1 }
            ?: throw VideoError("找不到第 $part P，请检查链接。")
        val cid = chosen.number("cid")?.takeIf { it > 0 } ?: throw VideoError("B 站未返回这一分 P 的播放信息。")
        val title = (view.text("title") ?: bvid) +
            if (pages.size > 1) " P$part ${chosen.text("part").orEmpty()}" else ""
        val durationSeconds = chosen.number("duration") ?: 0
        val path = "/x/player/playurl?bvid=$bvid&cid=$cid"
        val dashData = networkOrNull { biliApi("$path&fnval=16&qn=64") }
            ?: networkOrNull {
                val page = embedded ?: readPage(canonical, Source.BILI)
                val state = assignedObject(page.body, "__INITIAL_STATE__")
                // Only use embedded streams if the page confirms the requested part.
                if (state?.number("cid") == cid ||
                    (part == 1 && state?.get("videoData").obj()?.number("cid") == cid)) {
                    assignedObject(page.body, "__playinfo__")?.get("data").obj()
                } else null
            }
        val dash = dashData?.get("dash").obj()
        val failures = mutableListOf<IOException>()
        if (dash != null) {
            val audios = dash["audio"].items().mapNotNull { it.obj() }.sortedBy { it.number("bandwidth") ?: Long.MAX_VALUE }
            val videos = dash["video"].items().mapNotNull { it.obj() }.sortedWith(
                compareByDescending<JsonObject> { it.number("codecid") == 7L }
                    .thenByDescending { it.number("height")?.coerceAtMost(720) ?: 0 }
                    .thenBy { it.number("bandwidth") ?: Long.MAX_VALUE },
            )
            if (audios.isNotEmpty() && videos.isNotEmpty()) {
                val audio = File(target.parentFile, "audio.m4a")
                val video = File(target.parentFile, "track.mp4")
                try {
                    downloadFirst(audios.take(3).flatMap(::streamUrls), Source.BILI, audio, "正在下载音轨", update, "audio/")
                    downloadFirst(videos.take(6).flatMap(::streamUrls), Source.BILI, video, "正在下载画面", update,
                        "video/", MAX_BYTES - audio.length() - 64 * 1024)
                    update(Progress("正在合并画面和声音"))
                    muxTracks(video, audio, target)
                    validateVideo(target, "video/", requireAudio = true, durationSeconds = durationSeconds)
                    return title
                } catch (e: IOException) {
                    failures += e
                    target.delete()
                } finally {
                    audio.delete()
                    video.delete()
                }
            }
        }
        try {
            val progressive = biliApi("$path&qn=32&fnval=0&platform=html5")["durl"].items()
            if (progressive.size > 1) throw VideoError("平台只返回了分段流，无法保存为完整视频。")
            val streams = progressive.singleOrNull().obj()?.let(::streamUrls).orEmpty()
            if (streams.isEmpty()) throw VideoError("B 站未提供可下载的完整视频。")
            downloadFirst(streams, Source.BILI, target, "正在下载视频", update, "video/",
                requireAudio = true, durationSeconds = durationSeconds)
            return title
        } catch (e: IOException) {
            throw (failures.firstOrNull { it is VideoTooLarge } ?: e)
        }
    }

    private fun streamUrls(item: JsonObject) =
        listOfNotNull(item.text("baseUrl", "base_url", "url")) +
            (item["backupUrl"] ?: item["backup_url"]).items().mapNotNull { it.str() }

    private suspend fun biliApi(path: String): JsonObject {
        val body = readPage("https://api.bilibili.com$path".toHttpUrl(), Source.BILI).body
        val root = json(body) ?: throw IOException("B 站返回了无法读取的数据。")
        if (root.number("code") != 0L) throw VideoError("B 站拒绝访问或作品已不可用。")
        return root["data"].obj() ?: throw VideoError("B 站未返回播放数据。")
    }

    private suspend fun <T> networkOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    }

    private data class Page(val url: HttpUrl, val body: String)

    private suspend fun readPage(url: HttpUrl, source: Source): Page = request(url, source, page = true) { response ->
        val body = response.body ?: throw IOException("网页没有内容。")
        val stream = body.source()
        if (stream.request(4L * 1024 * 1024 + 1)) throw IOException("网页数据过大。")
        Page(response.request.url, stream.readUtf8())
    }

    private suspend fun downloadFirst(
        urls: List<String>, source: Source, target: File, label: String, update: (Progress) -> Unit,
        mimePrefix: String, limit: Long = MAX_BYTES, requireAudio: Boolean = false, durationSeconds: Long = 0,
    ) {
        val candidates = urls.mapNotNull(::httpsUrl).filter(source::hostsMedia).distinct().take(18)
        var error: IOException? = null
        for (url in candidates) {
            coroutineContext.ensureActive()
            try {
                request(url, source, page = false) { response ->
                    val body = response.body ?: throw IOException("视频地址未返回文件。")
                    val length = body.contentLength()
                    if (length > limit || limit <= 0) throw VideoTooLarge()
                    val contentRange = response.header("Content-Range")
                    val fullRange = contentRange?.let(::fullRangeSize)
                    if ((response.code != 200 || contentRange != null) && fullRange == null) {
                        throw IOException("平台只返回了部分文件。")
                    }
                    var total = 0L
                    var lastUpdate = 0L
                    update(Progress(label, 0, length))
                    body.byteStream().use { input ->
                        target.outputStream().buffered().use { output ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                total += count
                                if (total > limit) throw VideoTooLarge()
                                output.write(bytes, 0, count)
                                val now = System.nanoTime()
                                if (now - lastUpdate >= 150_000_000L) {
                                    update(Progress(label, total, length))
                                    lastUpdate = now
                                }
                            }
                        }
                    }
                    if (total == 0L || length >= 0 && total != length || fullRange != null && total != fullRange) {
                        throw IOException("视频下载不完整。")
                    }
                }
                validateVideo(target, mimePrefix, requireAudio, durationSeconds)
                return
            } catch (e: IOException) {
                target.delete()
                if (error !is VideoTooLarge) error = e
            }
        }
        if (error is VideoTooLarge) throw error
        throw VideoError(if (candidates.isEmpty()) "网页没有提供可用的 MP4 地址。"
            else error?.message?.takeIf { error is VideoError } ?: "视频地址不可用或文件不完整，请刷新分享链接后重试。")
    }

    private suspend fun <T> request(
        initial: HttpUrl, source: Source, page: Boolean, read: suspend (Response) -> T,
    ): T {
        var url = initial
        repeat(7) {
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                !(if (page) source.hostsPage(url) else source.hostsMedia(url))) {
                throw IOException("链接跳转到了不支持的地址。")
            }
            val req = Request.Builder().url(url).header("User-Agent", if (source == Source.BILI) DESKTOP_UA else MOBILE_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .apply { if (source.referer.isNotEmpty()) header("Referer", source.referer) }.build()
            val call = client.newCall(req)
            val result: Pair<HttpUrl?, T?> = coroutineScope {
                val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            val next = response.header("Location")?.let(url::resolve)
                                ?: throw IOException("分享链接无法跳转。")
                            next to null
                        } else {
                            if (!response.isSuccessful) throw IOException("请求失败（HTTP ${response.code}）。")
                            null to read(response)
                        }
                    }
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    throw e
                } finally {
                    cancelCall.cancel()
                }
            }
            if (result.first == null) {
                @Suppress("UNCHECKED_CAST")
                return result.second as T
            }
            url = result.first!!
        }
        throw IOException("分享链接跳转次数过多。")
    }
}

internal class WrongVideo : IOException("平台返回的作品与分享链接不一致。")
internal fun fullRangeSize(header: String): Long? {
    val match = Regex("""bytes 0-(\d+)/(\d+)""").matchEntire(header) ?: return null
    val end = match.groupValues[1].toLongOrNull() ?: return null
    return match.groupValues[2].toLongOrNull()?.takeIf { it > 0 && it - 1 == end }
}
internal const val MAX_BYTES = 1_000_000_000L
private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
