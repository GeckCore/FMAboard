package org.futo.inputmethod.latin.fmaboard

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the picture is cut before it becomes a sticker; coordinates go from 0 to 1. */
sealed class StickerCut {
    object None : StickerCut()
    data class Box(val rect: RectF) : StickerCut()
    data class Lasso(val points: List<PointF>) : StickerCut()
}

/** Cutting, background removal and "lo último de WhatsApp" for Crear sticker. */
object StickerEdit {
    /** [src] cut by [cut]: a rectangle, or the shape drawn by hand (outside it transparent). */
    fun applyCut(src: Bitmap, cut: StickerCut): Bitmap = when (cut) {
        StickerCut.None -> src
        is StickerCut.Box -> {
            val l = (cut.rect.left * src.width).roundToInt().coerceIn(0, src.width - 2)
            val t = (cut.rect.top * src.height).roundToInt().coerceIn(0, src.height - 2)
            val r = (cut.rect.right * src.width).roundToInt().coerceIn(l + 2, src.width)
            val b = (cut.rect.bottom * src.height).roundToInt().coerceIn(t + 2, src.height)
            Bitmap.createBitmap(src, l, t, r - l, b - t)
        }
        is StickerCut.Lasso -> {
            val path = Path()
            cut.points.forEachIndexed { i, p ->
                val x = p.x * src.width
                val y = p.y * src.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val bounds = RectF().also { path.computeBounds(it, true) }
            bounds.intersect(0f, 0f, src.width.toFloat(), src.height.toFloat())
            val w = max(2, bounds.width().roundToInt())
            val h = max(2, bounds.height().roundToInt())
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.translate(-bounds.left, -bounds.top)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            })
            out
        }
    }

    /** The smallest rectangle holding everything that isn't transparent. */
    fun trim(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                if ((px[o + x] ushr 24) > 24) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < l || b < t) return bitmap
        return Bitmap.createBitmap(bitmap, l, t, r - l + 1, b - t + 1)
    }

    /**
     * Frames of a video without background, all cut to the one rectangle that holds the subject
     * in every frame: it fills the sticker and doesn't jump around.
     */
    fun trimAll(frames: List<Bitmap>): List<Bitmap> {
        if (frames.isEmpty()) return frames
        val w = frames[0].width
        val h = frames[0].height
        var l = w; var t = h; var r = -1; var b = -1
        val px = IntArray(w * h)
        for (f in frames) {
            if (f.width != w || f.height != h) return frames
            f.getPixels(px, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                val o = y * w
                for (x in 0 until w) if ((px[o + x] ushr 24) > 24) {
                    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                }
            }
        }
        if (r < l || b < t) return frames
        return frames.map { Bitmap.createBitmap(it, l, t, r - l + 1, b - t + 1) }
    }

    // ------------------------------------------------------------ background removal

    @Volatile private var segmenter: SubjectSegmenter? = null

    private fun segmenter(): SubjectSegmenter = segmenter ?: synchronized(this) {
        segmenter ?: SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        ).also { segmenter = it }
    }

    /**
     * Makes sure the background model is on the phone (Play services downloads it once, a few
     * MB). Blocking; returns false if it can't be had right now.
     */
    fun ensureModel(context: Context): Boolean {
        val client = ModuleInstall.getClient(context)
        val seg = segmenter()
        return try {
            if (Tasks.await(client.areModulesAvailable(seg), 20, TimeUnit.SECONDS).areModulesAvailable()) return true
            Tasks.await(client.installModules(ModuleInstallRequest.newBuilder().addApi(seg).build()), 120, TimeUnit.SECONDS)
            // The install call returns once it starts; wait for it to be usable
            repeat(60) {
                if (Tasks.await(client.areModulesAvailable(seg), 10, TimeUnit.SECONDS).areModulesAvailable()) return true
                Thread.sleep(1000)
            }
            false
        } catch (e: Exception) {
            android.util.Log.w("FMAboardSticker", "Background model not available", e)
            false
        }
    }

    /** The main subject of [src] with the background made transparent (on the phone). Blocking. */
    fun removeBackground(src: Bitmap, maxSide: Int = 1024): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val input = if (scale < 1f) Bitmap.createScaledBitmap(src, max(2, (src.width * scale).roundToInt()),
            max(2, (src.height * scale).roundToInt()), true) else src
        // The model doesn't look at transparency: a hand cut is kept by cutting the result again
        val result = Tasks.await(segmenter().process(InputImage.fromBitmap(input.copy(Bitmap.Config.ARGB_8888, false), 0)), 30, TimeUnit.SECONDS)
        val fg = result.foregroundBitmap ?: return input
        if (!input.hasAlpha()) return fg
        val out = fg.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(input, 0f, 0f, Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        })
        return out
    }

    // ------------------------------------------------------------ lo último de WhatsApp

    class Recent(val uri: Uri, val isVideo: Boolean, val thumb: Bitmap?)

    /** Photos, videos and GIFs received in WhatsApp lately, newest first (needs media permission). */
    fun recentWhatsApp(context: Context, limit: Int = 15): List<Recent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = ArrayList<Pair<Recent, Long>>()
        for ((collection, isVideo) in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to false,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to true,
        )) {
            val cursor = try {
                context.contentResolver.query(collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
                    arrayOf("%WhatsApp%", "%Sent%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            } catch (e: Exception) { null } ?: continue
            cursor.use {
                var n = 0
                while (it.moveToNext() && n++ < limit) {
                    val uri = ContentUris.withAppendedId(collection, it.getLong(0))
                    out.add(Recent(uri, isVideo, null) to it.getLong(1))
                }
            }
        }
        return out.sortedByDescending { it.second }.take(limit).map { (r, _) ->
            val thumb = try { context.contentResolver.loadThumbnail(r.uri, Size(240, 240), null) } catch (e: Exception) { null }
            Recent(r.uri, r.isVideo, thumb)
        }
    }

    /** Scales [b] so its longest side is [side] at most, for drawing on screen. */
    fun fitForScreen(b: Bitmap, side: Int): Bitmap {
        val s = min(1f, side.toFloat() / max(b.width, b.height))
        if (s >= 1f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { setScale(s, s) }, true)
    }
}
