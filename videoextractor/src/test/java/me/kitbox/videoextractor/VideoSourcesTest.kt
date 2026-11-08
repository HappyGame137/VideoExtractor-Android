package me.kitbox.videoextractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourcesTest {
    @Test fun onlyAResponseThatCoversEveryByteIsComplete() {
        assertEquals(8192L, fullRangeSize("bytes 0-8191/8192"))
        assertNull(fullRangeSize("bytes 0-4095/8192"))
        assertNull(fullRangeSize("bytes 4096-8191/8192"))
        assertNull(fullRangeSize("bytes 0-8191/*"))
    }

    @Test fun shareTextPreservesQueryAndSkipsUnrelatedLinks() {
        val link = parseLink("参考 https://example.org/a，视频：https://www.bilibili.com/video/BV1GJ411x7h7?p=2。")!!
        assertEquals(Source.BILI, link.source)
        assertEquals("2", link.url.queryParameter("p"))
        assertEquals(Source.BILI, parseLink("BV1GJ411x7h7?p=2")?.source)
        assertEquals(Source.DIRECT, parseLink("https://example.org/a.mp4?signature=example")?.source)
        assertEquals(Source.XHS, parseLink("http://xhslink.cn/o/example")?.source)
        assertNull(parseLink("https://bilibili.com.example.org/video/BV1GJ411x7h7"))
    }

    @Test fun douyinParserUsesRequestedVideoOverRecommendations() {
        val html = """<script>window._ROUTER_DATA = {"items":[
            {"aweme_id":"7999999999999999999","video":{"play_addr":{"url_list":["https://v.douyinvod.com/wrong.mp4"]}}},
            {"aweme_id":"7123456789012345678","desc":"带花括号 } 的标题","video":{"playAddr":{"urlList":["https://v.douyinvod.com/right.mp4"]}}}
        ]};</script>"""
        val video = douyinVideo(html, "7123456789012345678")!!
        assertEquals("带花括号 } 的标题", video.title)
        assertEquals(listOf("https://v.douyinvod.com/right.mp4"), video.urls)
        assertNull(douyinVideo(html, "7000000000000000000"))
    }

    @Test fun xhsParserTakesVideoStreamAndRejectsMismatchedMetadata() {
        val id = "0123456789abcdef01234567"
        val html = """<script>window.__INITIAL_STATE__ = {"note":{"noteId":"$id","title":"示例笔记",
            "video":{"media":{"stream":{"h264":[{"masterUrl":"https://sns-video.xhscdn.com/note.mp4"}]}}},
            "imageList":[{"urlDefault":"https://sns-img.xhscdn.com/poster.jpg"}]}};</script>"""
        assertEquals(listOf("https://sns-video.xhscdn.com/note.mp4"), xhsVideo(html, id)?.urls)
        assertNull(xhsVideo(html, "111111111111111111111111"))
        assertNull(xhsMetaVideo(mapOf("og:url" to "https://www.xiaohongshu.com/explore/111111111111111111111111",
            "og:video" to "https://sns-video.xhscdn.com/wrong.mp4"), id))
        assertTrue(Source.XHS.hostsMedia(httpsUrl("https://sns-video.xhscdn.com/note.mp4")!!))
    }
}
