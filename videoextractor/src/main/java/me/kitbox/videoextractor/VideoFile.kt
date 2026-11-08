package me.kitbox.videoextractor

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

internal fun validateVideo(file: File, mimePrefix: String, requireAudio: Boolean = false, durationSeconds: Long = 0) {
    val header = ByteArray(12)
    try {
        java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
    } catch (_: java.io.EOFException) {
        throw VideoError("下载的视频文件不完整。")
    }
    if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") {
        throw VideoError("地址返回的内容不是 MP4 视频。")
    }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
        val index = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true }
        if (index == -1) throw VideoError("文件缺少可播放的${if (mimePrefix == "audio/") "音轨" else "画面"}。")
        val tracks = if (requireAudio) listOf(index, formats.indexOfFirst {
            it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }) else listOf(index)
        if (tracks.any { it < 0 }) throw VideoError("平台提供的文件没有音轨。")
        for (track in tracks) {
            extractor.selectTrack(track)
            if (extractor.sampleSize <= 0 || extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                throw VideoError("文件没有可读取的媒体数据。")
            }
            extractor.unselectTrack(track)
        }
        val format = formats[index]
        if (durationSeconds > 0 && format.containsKey(MediaFormat.KEY_DURATION) &&
            format.getLong(MediaFormat.KEY_DURATION) + 2_000_000L < durationSeconds * 1_000_000L) {
            throw VideoError("视频时长短于原作品，未保存不完整的文件。")
        }
    } catch (e: VideoError) {
        throw e
    } catch (_: Exception) {
        throw VideoError("文件无法播放，可能已损坏或格式不受支持。")
    } finally {
        extractor.release()
    }
}

internal suspend fun muxTracks(video: File, audio: File, target: File) {
    val extractors = listOf(MediaExtractor(), MediaExtractor())
    var muxer: MediaMuxer? = null
    try {
        val writer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = writer
        val outputTracks = listOf(video to "video/", audio to "audio/").mapIndexed { i, (file, prefix) ->
            val extractor = extractors[i]
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            }
            extractor.selectTrack(track)
            writer.addTrack(extractor.getTrackFormat(track))
        }
        writer.start()
        var buffer = ByteBuffer.allocateDirect(256 * 1024)
        val info = MediaCodec.BufferInfo()
        for ((i, extractor) in extractors.withIndex()) {
            while (extractor.sampleSize >= 0L) {
                coroutineContext.ensureActive()
                val size = extractor.sampleSize
                if (size > 16 * 1024 * 1024L) throw VideoError("视频编码超出手机支持范围。")
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val flags = extractor.sampleFlags
                if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw VideoError("视频轨道受保护。")
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                val codecFlags =
                    (if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or
                    (if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0)
                info.set(0, count, extractor.sampleTime.coerceAtLeast(0L), codecFlags)
                writer.writeSampleData(outputTracks[i], buffer, info)
                extractor.advance()
            }
        }
        writer.stop()
        if (target.length() > MAX_BYTES) throw VideoTooLarge()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        throw e
    } catch (_: Exception) {
        throw VideoError("手机无法合并这组音视频轨道。")
    } finally {
        extractors.forEach { it.release() }
        muxer?.let { runCatching { it.release() } }
    }
}

internal suspend fun saveVideo(resolver: ContentResolver, file: File, title: String): SavedVideo {
    val safeName = title.replace(Regex("""[\\/:*?"<>|\p{Cc}\p{Cf}]"""), " ")
        .replace(Regex("""\s+"""), " ").trim(' ', '.').take(64).trimEnd().ifBlank { "视频" }
    val name = "$safeName.mp4"
    coroutineContext.ensureActive()
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/视频提取/")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }) ?: throw VideoError("无法在相册里创建视频，请检查剩余空间。")
    var published = false
    try {
        val out = resolver.openOutputStream(uri) ?: throw VideoError("无法写入相册。")
        out.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        coroutineContext.ensureActive()
        val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        if (updated != 1) throw VideoError("相册未能完成保存。")
        published = true
        return SavedVideo(uri, name, file.length())
    } finally {
        if (!published) runCatching { resolver.delete(uri, null, null) }
    }
}
