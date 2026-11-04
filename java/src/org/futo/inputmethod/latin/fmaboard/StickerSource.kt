package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A photo, video or GIF to make a sticker from. */
sealed class StickerSource {
    /** 0 for a still image. */
    abstract val durationMs: Long
    val isAnimated get() = durationMs > 0

    /** The picture at [ms], scaled so its short side is about [shortSide] pixels. */
    abstract fun frameAt(ms: Long, shortSide: Int): Bitmap?
    open fun release() {}

    /** [count] pictures from [startMs] at [fps], in order; stops early if [onFrame] says false. */
    open fun frames(startMs: Long, count: Int, fps: Int, shortSide: Int, onFrame: (Int, Bitmap) -> Boolean) {
        for (i in 0 until count) {
            val f = frameAt(startMs + i * 1000L / fps, shortSide) ?: continue
            if (!onFrame(i, f)) return
        }
    }

    class Still(private val bitmap: Bitmap) : StickerSource() {
        override val durationMs = 0L
        override fun frameAt(ms: Long, shortSide: Int) = bitmap
    }

    class Video(private val retriever: MediaMetadataRetriever, override val durationMs: Long,
                private val width: Int, private val height: Int,
                private val context: Context, private val uri: Uri) : StickerSource() {
        /** Decoding in order is many times faster than asking for each picture on its own. */
        override fun frames(startMs: Long, count: Int, fps: Int, shortSide: Int, onFrame: (Int, Bitmap) -> Boolean) {
            val done = try {
                VideoFrames.decode(context, uri, startMs, count, fps, shortSide, onFrame)
            } catch (e: Exception) {
                android.util.Log.w("FMAboardSticker", "Fast decoding failed, using the slow way", e)
                -1
            }
            if (done < 0) super.frames(startMs, count, fps, shortSide, onFrame)
        }

        override fun frameAt(ms: Long, shortSide: Int): Bitmap? {
            val scale = min(1f, shortSide.toFloat() / min(width, height))
            val w = max(2, (width * scale).roundToInt())
            val h = max(2, (height * scale).roundToInt())
            return synchronized(retriever) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                    retriever.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                else retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }
        override fun release() { try { retriever.release() } catch (_: Exception) {} }
    }

    @Suppress("DEPRECATION")
    class Gif(private val movie: Movie, override val durationMs: Long) : StickerSource() {
        override fun frameAt(ms: Long, shortSide: Int): Bitmap? = synchronized(movie) {
            val scale = shortSide.toFloat() / min(movie.width(), movie.height())
            val w = max(2, (movie.width() * scale).roundToInt())
            val h = max(2, (movie.height() * scale).roundToInt())
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.TRANSPARENT)
            canvas.scale(scale, scale)
            movie.setTime((ms % max(1, durationMs)).toInt())
            movie.draw(canvas, 0f, 0f)
            out
        }
    }

    companion object {
        /** Reads [uri]; throws with a message for the user if it can't be used. */
        fun open(context: Context, uri: Uri): StickerSource {
            val resolver = context.contentResolver
            val head = ByteArray(16)
            val read = resolver.openInputStream(uri)?.use { it.read(head) } ?: -1
            if (read <= 0) throw IllegalArgumentException("No se pudo abrir el archivo")
            val type = resolver.getType(uri) ?: ""
            val isGif = String(head, 0, 3, Charsets.US_ASCII) == "GIF"
            val isVideo = type.startsWith("video/") ||
                (read >= 8 && String(head, 4, 4, Charsets.US_ASCII).let { it == "ftyp" }) ||
                (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte()) // webm / mkv

            if (isGif) {
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                @Suppress("DEPRECATION")
                val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                if (movie != null && movie.width() > 0 && movie.duration() > 0) return Gif(movie, movie.duration().toLong())
            }
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file") retriever.setDataSource(uri.path)
                    else retriever.setDataSource(context, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
                    if (duration <= 0 || w <= 0 || h <= 0) throw IllegalArgumentException("Este vídeo no se puede leer")
                    return Video(retriever, duration, w, h, context.applicationContext, uri)
                } catch (e: Exception) {
                    retriever.release()
                    throw if (e is IllegalArgumentException) e else IllegalArgumentException("Este vídeo no se puede leer")
                }
            }
            val bitmap = OcrCapture.loadBitmap(context, uri) ?: throw IllegalArgumentException("No se pudo abrir la imagen")
            return Still(bitmap)
        }

        /** Copies a shared file to our cache, since the permission to read it ends with the share. */
        fun copyToCache(context: Context, uri: Uri): Uri {
            val dir = File(context.cacheDir, "stickers").also { it.mkdirs() }
            val file = File(dir, "source")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            return Uri.fromFile(file)
        }
    }
}
