package me.kitbox.videoextractor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import kotlin.coroutines.resume

// Observe only the content requests made by the public page. The page cannot call Android code.
private val OBSERVE_PUBLIC_JSON = """
    (() => {
        if (window.top !== window) return;
        const matches = u => {
            try {
                const v = new URL(u, location.href);
                return (v.origin === location.origin && v.pathname.endsWith('/aweme/detail/')) ||
                    (v.protocol === 'https:' &&
                     (v.hostname === 'xiaohongshu.com' || v.hostname.endsWith('.xiaohongshu.com')) &&
                     v.pathname === '/api/sns/web/v1/feed');
            } catch (_) { return false; }
        };
        const results = [];
        Object.defineProperty(window, '__videoExtractorData', { value: results });
        const store = (raw) => {
            try {
                if (typeof raw === 'string' && raw.length < 1500000 && results.length < 3)
                    results.push(JSON.parse(raw));
            } catch (_) {}
        };
        const fetch = window.fetch;
        window.fetch = function(input, ...args) {
            return fetch.call(this, input, ...args).then(response => {
                if (response.ok && matches(response.url)) {
                    try { response.clone().text().then(store).catch(() => {}); } catch (_) {}
                }
                return response;
            });
        };
        const open = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(...args) {
            if (matches(args[1])) this.addEventListener('load', () => {
                if (this.status === 200 && matches(this.responseURL)) {
                    try { store(this.responseType === 'json' ? JSON.stringify(this.response) : this.responseText); }
                    catch (_) {}
                }
            });
            return open.apply(this, args);
        };
    })()
""".trimIndent()

private val SNAPSHOT = """
    (() => {
        if (!document.body) return null;
        const meta = {};
        document.querySelectorAll('meta[property^="og:"]').forEach(e => {
            meta[e.getAttribute('property')] = (e.content || '').slice(0, 8192);
        });
        const bounded = value => {
            try { const raw = JSON.stringify(value); return raw && raw.length <= 2000000 ? value : null; }
            catch (_) { return null; }
        };
        return {
            url: location.href, meta: meta, state: bounded(window.__INITIAL_STATE__),
            router: bounded(window._ROUTER_DATA), responses: bounded(window.__videoExtractorData),
            render: (document.getElementById('RENDER_DATA')?.textContent || '').slice(0, 2000000)
        };
    })()
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun readInBrowser(context: Context, source: Source, url: HttpUrl, expected: String?): PageVideo? =
    withContext(Dispatchers.Main) {
        val browser = try { WebView(context) } catch (_: RuntimeException) { return@withContext null }
        var rendererGone = false
        try {
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                userAgentString = if (source == Source.DOUYIN)
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                else userAgentString.replace("; wv", "").replace(" Version/4.0", "")
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && (request.url.scheme != "https" ||
                        httpsUrl(request.url.toString())?.let(source::hostsPage) != true)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone = true
                    return true
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val origins = source.domains.flatMap { listOf("https://$it", "https://*.$it") }.toSet()
                WebViewCompat.addDocumentStartJavaScript(browser, OBSERVE_PUBLIC_JSON, origins)
            }
            browser.loadUrl(url.toString())
            withTimeoutOrNull(17_000) {
                while (!rendererGone) {
                    delay(700)
                    if (rendererGone) break
                    val raw = suspendCancellableCoroutine<String?> { continuation ->
                        browser.evaluateJavascript(SNAPSHOT) { if (continuation.isActive) continuation.resume(it) }
                    }
                    val page = withContext(Dispatchers.Default) { raw?.let { browserVideo(it, source, expected) } }
                    if (page != null) return@withTimeoutOrNull page
                }
                null
            }
        } finally {
            if (!rendererGone) browser.stopLoading()
            browser.destroy()
        }
    }

private fun browserVideo(raw: String, source: Source, expected: String?): PageVideo? {
    val snapshot = json(raw) ?: return null
    val current = snapshot.text("url")?.let(::httpsUrl)?.takeIf(source::hostsPage) ?: return null
    if (source == Source.XHS && (current.encodedPath == "/login" || current.encodedPath.startsWith("/404"))) {
        throw VideoError("小红书没有开放此分享页。请从视频笔记重新复制完整链接；需登录的内容暂不支持。")
    }
    val id = if (source == Source.DOUYIN) current.douyinId() else current.xhsId()
    if (id == null) return null
    if (expected != null && id != expected) throw WrongVideo()
    val roots = sequenceOf(snapshot["state"].obj(), snapshot["router"].obj()) +
        snapshot["responses"].items().asSequence().map { it.obj() }
    return if (source == Source.DOUYIN) {
        douyinVideo(roots, id) ?: snapshot.text("render")?.let { douyinVideo("<script id='RENDER_DATA'>$it</script>", id) }
    } else {
        val meta = snapshot["meta"].obj()?.entrySet()?.mapNotNull { (key, value) -> value.str()?.let { key to it } }
            ?.toMap().orEmpty()
        xhsVideo(roots, id) ?: xhsMetaVideo(meta, id)
    }
}
