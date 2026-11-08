package me.kitbox.videoextractor

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

internal enum class Source(val title: String, val referer: String, vararg val domains: String) {
    BILI("哔哩哔哩", "https://www.bilibili.com/", "bilibili.com", "b23.tv"),
    DOUYIN("抖音", "https://www.douyin.com/", "douyin.com", "iesdouyin.com"),
    XHS("小红书", "https://www.xiaohongshu.com/", "xiaohongshu.com", "xhslink.com", "xhslink.cn"),
    DIRECT("MP4 直链", "");

    fun hostsPage(url: HttpUrl) = url.onDomain(*domains)
    fun hostsMedia(url: HttpUrl) = hostsPage(url) || when (this) {
        BILI -> url.onDomain("bilivideo.com", "bilivideo.cn", "hdslb.com", "biliapi.net", "akamaized.net")
        DOUYIN -> url.onDomain("douyinvod.com", "douyincdn.com", "bytecdn.cn", "amemv.com",
            "snssdk.com", "bytedance.com", "byteoversea.com", "douyinpic.com")
        XHS -> url.onDomain("xhscdn.com", "xhscdn.net")
        DIRECT -> true
    }
}

internal data class VideoLink(val url: HttpUrl, val source: Source)
internal class VideoError(message: String) : java.io.IOException(message)
internal class VideoTooLarge : java.io.IOException("视频超过 1 GB，无法保存。")

internal fun HttpUrl.onDomain(vararg suffixes: String) =
    suffixes.any { host == it || host.endsWith(".$it") }

internal fun parseLink(text: String): VideoLink? {
    val value = text.trim().take(16_384)
    val bare = Regex("(?i)^(BV[0-9A-Za-z]{10}|av[0-9]+)(?:[?]p=[1-9][0-9]*)?$")
    if (bare.matches(value)) return VideoLink(
        "https://www.bilibili.com/video/$value".toHttpUrlOrNull()!!, Source.BILI,
    )
    val links = Regex("""(?i)https?://[^\s\p{Z}<>"'，。！？、；：）】》]+""").findAll(value)
    return links.firstNotNullOfOrNull { match ->
        val url = httpsUrl(match.value.trimEnd('.', ',', '!', ';', ')', ']', '}')) ?: return@firstNotNullOfOrNull null
        val source = Source.entries.firstOrNull { it.hostsPage(url) }
            ?: Source.DIRECT.takeIf { url.encodedPath.endsWith(".mp4", true) }
        source?.let { VideoLink(url.newBuilder().fragment(null).build(), it) }
    }
}

internal fun httpsUrl(raw: String): HttpUrl? {
    val value = raw.trim().replace("&amp;", "&")
    val normalized = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", true) -> "https://${value.substring(7)}"
        else -> value
    }
    return normalized.takeIf { it.length <= 8_192 }?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
    }
}

internal fun HttpUrl.douyinId(): String? =
    Regex("""/(?:video|note|slides)/(\d{10,24})(?:/|$)""").find(encodedPath)?.groupValues?.get(1)
        ?: listOf("modal_id", "aweme_id", "item_id").firstNotNullOfOrNull { key ->
            queryParameter(key)?.takeIf { it.matches(Regex("""\d{10,24}""")) }
        }

internal fun HttpUrl.xhsId(): String? =
    Regex("""^/(?:explore|discovery/item)/([0-9a-f]{24})(?:/|$)""", RegexOption.IGNORE_CASE)
        .find(encodedPath)?.groupValues?.get(1)?.lowercase()

internal fun JsonElement?.obj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonElement?.items(): List<JsonElement> = this?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
internal fun JsonElement?.str(): String? = this?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.text(vararg keys: String) = keys.firstNotNullOfOrNull { get(it).str()?.takeIf(String::isNotBlank) }
internal fun JsonObject.number(key: String) = text(key)?.toLongOrNull()
internal fun json(raw: String): JsonObject? = runCatching { JsonParser.parseString(raw).obj() }.getOrNull()

internal fun assignedObject(html: String, name: String): JsonObject? {
    val assignments = Regex("""(?:\bwindow\s*\.\s*)?\b${Regex.escape(name)}\s*=\s*\{""").findAll(html)
    for (match in assignments.take(4)) {
        val start = match.range.last
        var depth = 0
        var quote = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quote) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quote = false
            } else {
                when (c) {
                    '"' -> quote = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return json(html.substring(start, i + 1))
                }
            }
        }
    }
    return null
}

private fun walk(node: JsonElement?, depth: Int = 0): Sequence<JsonObject> = sequence {
    if (depth > 30 || node == null) return@sequence
    if (node.isJsonObject) {
        yield(node.asJsonObject)
        node.asJsonObject.entrySet().forEach { yieldAll(walk(it.value, depth + 1)) }
    } else if (node.isJsonArray) {
        node.asJsonArray.forEach { yieldAll(walk(it, depth + 1)) }
    }
}

internal data class PageVideo(val title: String, val urls: List<String>)

internal fun douyinVideo(html: String, expected: String): PageVideo? {
    val render = Regex("""<script[^>]*id=["'](?:RENDER_DATA|__UNIVERSAL_DATA_FOR_REHYDRATION__)["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val roots = sequenceOf(assignedObject(html, "_ROUTER_DATA"), assignedObject(html, "__INITIAL_STATE__")) +
        render.findAll(html).take(4).map { match ->
            val raw = match.groupValues[1].trim()
            json(if (raw.startsWith("%")) runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) else raw)
        }
    return douyinVideo(roots, expected)
}

internal fun douyinVideo(roots: Sequence<JsonObject?>, expected: String): PageVideo? {
    val items = roots.flatMap { walk(it) }.filter { it.text("aweme_id", "awemeId", "item_id", "itemId") == expected }
    for (item in items) {
        if (item["images"].items().isNotEmpty() || item["image_post_info"].obj() != null) {
            throw VideoError("这是图文作品，没有可提取的视频。")
        }
        val video = item["video"].obj() ?: continue
        val playKeys = listOf("play_addr_h264", "playAddrH264", "play_addr", "playAddr", "play_addr_lowbr",
            "playAddrLowbr", "download_addr", "downloadAddr")
        val encodings = (video["bit_rate"] ?: video["bitRate"]).items().mapNotNull { it.obj() }
        val urls = (listOf(video) + encodings).flatMap { stream ->
            playKeys.flatMap { key ->
                val field = stream[key]
                val node = field.obj()
                listOfNotNull(field.str(), node?.text("url", "src")) +
                    (node?.get("url_list") ?: node?.get("urlList")).items().mapNotNull { it.str() }
            }
        }.distinct()
        if (urls.isNotEmpty()) return PageVideo(item.text("desc", "description", "title") ?: "抖音_$expected", urls)
    }
    return null
}

internal fun xhsVideo(html: String, expected: String): PageVideo? {
    val root = assignedObject(html, "__INITIAL_STATE__")
    xhsVideo(sequenceOf(root), expected)?.let { return it }

    // Some share pages expose a playable URL only in Open Graph metadata.
    val tags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
    val attributes = Regex("""([\w:-]+)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
    val meta = tags.mapNotNull { tag ->
        val fields = attributes.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
        val name = fields["property"] ?: fields["name"]
        if (name != null && fields["content"] != null) name.lowercase() to fields.getValue("content") else null
    }.toMap()
    return xhsMetaVideo(meta, expected)
}

internal fun xhsVideo(roots: Sequence<JsonObject?>, expected: String): PageVideo? {
    for (item in roots.flatMap { walk(it) }.filter { it.text("noteId", "note_id", "id")?.lowercase() == expected }) {
        val video = (item["video"] ?: item["videoInfo"]).obj()
        if (video == null) {
            if (item["imageList"].items().isNotEmpty() && item.text("type") == "normal") {
                throw VideoError("这是图文笔记，没有可提取的视频。")
            }
            continue
        }
        val urls = walk(video).flatMap { stream ->
            sequenceOf(stream.text("masterUrl", "master_url", "mediaUrl")) +
                (stream["backupUrls"] ?: stream["backup_urls"]).items().asSequence().map { it.str() }
        }.filterNotNull().distinct().take(20).toList()
        if (urls.isNotEmpty()) return PageVideo(item.text("title", "displayTitle", "desc") ?: "小红书_$expected", urls)
    }
    return null
}

internal fun xhsMetaVideo(meta: Map<String, String>, expected: String): PageVideo? {
    if (meta["og:url"]?.let { httpsUrl(it)?.takeIf(Source.XHS::hostsPage)?.xhsId() != expected } == true) return null
    val urls = listOfNotNull(meta["og:video:secure_url"], meta["og:video:url"], meta["og:video"])
    return urls.takeIf { it.isNotEmpty() }?.let { PageVideo(meta["og:title"] ?: "小红书_$expected", it) }
}
