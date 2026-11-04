package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pulls evenly spaced pictures out of a video by decoding it once, in order. */
internal object VideoFrames {
    /**
     * Calls [onFrame] for each of [count] pictures from [startMs] at [fps], scaled so the short
     * side is about [shortSide]. Returns how many were delivered, or -1 if it couldn't decode.
     */
    fun decode(context: Context, uri: Uri, startMs: Long, count: Int, fps: Int, shortSide: Int,
               onFrame: (Int, Bitmap) -> Boolean): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return -1
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val startUs = startMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val halfStepUs = 500_000L / fps
            fun wanted(i: Int) = startUs + i * 1_000_000L / fps - halfStepUs
            var next = 0
            var inputDone = false
            var idle = 0
            while (next < count) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex < 0) {
                    if (inputDone && ++idle > 200) break
                    continue
                }
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.presentationTimeUs >= wanted(next)) {
                    val image = codec.getOutputImage(outIndex)
                    val bitmap = image?.let { toBitmap(it, shortSide, rotation) }
                    image?.close()
                    if (bitmap != null) {
                        // A source with fewer pictures per second repeats one to keep the timing
                        while (next < count && info.presentationTimeUs >= wanted(next)) {
                            if (!onFrame(next, bitmap)) { codec.releaseOutputBuffer(outIndex, false); return next + 1 }
                            next++
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (eos) break
            }
            return if (next == 0) -1 else next
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** YUV → RGB with nearest sampling straight to the size wanted, then turned upright. */
    private fun toBitmap(image: Image, shortSide: Int, rotation: Int): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val crop = image.cropRect
        val srcW = crop.width()
        val srcH = crop.height()
        val scale = min(1f, shortSide.toFloat() / min(srcW, srcH))
        val w = max(2, (srcW * scale).roundToInt())
        val h = max(2, (srcH * scale).roundToInt())

        val planes = image.planes
        val y = ByteArray(planes[0].buffer.remaining()).also { planes[0].buffer.get(it) }
        val u = ByteArray(planes[1].buffer.remaining()).also { planes[1].buffer.get(it) }
        val v = ByteArray(planes[2].buffer.remaining()).also { planes[2].buffer.get(it) }
        val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride

        val out = IntArray(w * h)
        val xs = IntArray(w) { crop.left + it * srcW / w }
        for (dy in 0 until h) {
            val sy = crop.top + dy * srcH / h
            val yBase = sy * yRow
            val uBase = (sy / 2) * uRow
            val vBase = (sy / 2) * vRow
            val o = dy * w
            for (dx in 0 until w) {
                val sx = xs[dx]
                val yy = ((y[yBase + sx * yPix].toInt() and 0xff) - 16).coerceAtLeast(0) * 1192
                val cu = (u[uBase + (sx / 2) * uPix].toInt() and 0xff) - 128
                val cv = (v[vBase + (sx / 2) * vPix].toInt() and 0xff) - 128
                val r = ((yy + 1634 * cv) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * cv - 400 * cu) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * cu) shr 10).coerceIn(0, 255)
                out[o + dx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(false) }
        if (rotation % 360 == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
}
