package me.kitbox.videoextractor

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtractionDeviceTest {
    // Opt-in network smoke test. Only its own MediaStore entry is deleted.
    @Test fun extractAndReadBackFromGallery() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val input = args.getString("videoLink").orEmpty()
        assumeTrue(input.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stages = mutableSetOf<String>()
        val saved = VideoExtractor(context).extract(checkNotNull(parseLink(input))) { stages += it.label }
        val media = MediaExtractor()
        try {
            assertTrue(saved.bytes > 0)
            media.setDataSource(context, saved.uri, null)
            val tracks = (0 until media.trackCount).map { media.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            assertTrue(tracks.any { it?.startsWith("video/") == true })
            if (args.getString("expectAudio") == "true") assertTrue(tracks.any { it?.startsWith("audio/") == true })
            if (args.getString("expectMux") == "true") assertTrue("正在合并画面和声音" in stages)
            android.util.Log.i("VideoExtractorTest", "Saved ${saved.bytes} bytes, tracks=$tracks, stages=$stages")
        } finally {
            media.release()
            context.contentResolver.delete(saved.uri, null, null)
        }
        Unit
    }
}
