package org.futo.inputmethod.latin.fmaboard

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WhatsApp stickers: 512×512 WebP with a transparent margin, static (≤100 KB) or animated
 * (≤500 KB, ≤10 s), carrying the pack name and author in an EXIF chunk the same way
 * WhatsApp's own sticker apps and bots do.
 *
 * Android can only write single-frame WebP, so animated stickers are put together here:
 * each frame is encoded by [Bitmap.compress] and its bitstream is moved into an ANMF chunk.
 */
object StickerMaker {
    const val SIZE = 512
    const val PACK_NAME = "FMAboard"
    const val PUBLISHER = "FMAboard"
    private const val PACK_ID = "org.futo.fmaboard.stickers"
    const val MAX_STATIC_BYTES = 100_000
    const val MAX_ANIMATED_BYTES = 490_000
    const val MAX_ANIMATED_MS = 8_000L

    enum class Shape(val label: String) { FULL("Completa"), SQUARE("Cuadrada"), CIRCLE("Redonda") }

    /** A frame drawn on the sticker canvas: only [rect] is stored (the rest is transparent). */
    class Frame(val bitmap: Bitmap, val rect: Rect)

    /** Frames per second for an animation of this length: short ones can afford to be smoother. */
    fun fpsFor(durationMs: Long) = when {
        durationMs <= 2_500 -> 15
        durationMs <= 5_000 -> 12
        durationMs <= 6_500 -> 10
        else -> 8
    }

    // ----------------------------------------------------------------- drawing

    /** Where [src] goes on a [size]×[size] canvas for [shape]; even coordinates, as ANMF needs. */
    private fun placement(src: Bitmap, shape: Shape, size: Int, extra: Int = 0): Rect {
        val margin = size * 16 / SIZE + extra
        val box = size - 2 * margin
        if (shape != Shape.FULL) return Rect(margin, margin, margin + box, margin + box)
        val scale = min(box.toFloat() / src.width, box.toFloat() / src.height)
        val w = (src.width * scale).roundToInt().coerceIn(2, box) and 1.inv()
        val h = (src.height * scale).roundToInt().coerceIn(2, box) and 1.inv()
        val left = ((size - w) / 2) and 1.inv()
        val top = ((size - h) / 2) and 1.inv()
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Draws [src] for a sticker of [size] pixels: fitted whole, cropped square with round
     * corners, or cropped round; [caption] goes over the bottom in white with a black edge.
     */
    fun draw(src: Bitmap, shape: Shape, caption: String?, size: Int = SIZE, opaqueCard: Boolean = false,
             outline: Boolean = false): Frame {
        // The white edge needs room around the picture: an even width, so frames stay aligned
        val ring = if (outline) (size * 12 / SIZE + 1) and 1.inv() else 0
        var rect = placement(src, shape, size, ring)
        val out = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dst = RectF(0f, 0f, rect.width().toFloat(), rect.height().toFloat())

        val matrix = Matrix()
        if (shape == Shape.FULL) {
            matrix.setRectToRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), dst, Matrix.ScaleToFit.FILL)
        } else {
            // centre crop
            val side = min(src.width, src.height).toFloat()
            val crop = RectF((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
            matrix.setRectToRect(crop, dst, Matrix.ScaleToFit.FILL)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        paint.shader = shader
        when {
            shape == Shape.CIRCLE -> canvas.drawOval(dst, paint)
            shape == Shape.SQUARE || opaqueCard -> {
                val r = rect.width() * 0.08f
                canvas.drawRoundRect(dst, r, r, paint)
            }
            else -> canvas.drawRect(dst, paint)
        }
        paint.shader = null

        var result = out
        if (ring > 0) {
            // A white edge following the outline, like printed stickers: the shape's silhouette
            // stamped in white all around it, then the picture on top
            result = Bitmap.createBitmap(out.width + 2 * ring, out.height + 2 * ring, Bitmap.Config.ARGB_8888)
            val c = Canvas(result)
            val alpha = out.extractAlpha()
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val steps = 24
            for (k in 0 until steps) {
                val a = 2 * Math.PI * k / steps
                c.drawBitmap(alpha, ring + (ring * Math.cos(a)).toFloat(), ring + (ring * Math.sin(a)).toFloat(), white)
            }
            c.drawBitmap(alpha, ring.toFloat(), ring.toFloat(), white)
            c.drawBitmap(out, ring.toFloat(), ring.toFloat(), null)
            rect = Rect(rect.left - ring, rect.top - ring, rect.right + ring, rect.bottom + ring)
        }

        if (!caption.isNullOrBlank()) {
            val c = Canvas(result)
            c.translate(ring.toFloat(), ring.toFloat())
            drawCaption(c, caption.trim(), out.width, out.height, size)
        }

        // An opaque frame has no alpha chunk, which makes animations much smaller
        if (shape == Shape.FULL && !opaqueCard && !outline && !src.hasAlpha()) result.setHasAlpha(false)
        return Frame(result, rect)
    }

    private fun drawCaption(canvas: Canvas, text: String, w: Int, h: Int, size: Int) {
        val maxWidth = w * 0.92f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var textSize = size * 0.13f
        var lines: List<String>
        while (true) {
            fill.textSize = textSize
            lines = wrap(text, fill, maxWidth)
            if ((lines.size <= 3 && lines.all { fill.measureText(it) <= maxWidth }) || textSize < size * 0.045f) break
            textSize *= 0.9f
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = textSize / 6
            strokeJoin = Paint.Join.ROUND
        }
        val lineHeight = textSize * 1.12f
        var y = h - size * 0.045f - (lines.size - 1) * lineHeight - fill.descent()
        for (line in lines) {
            canvas.drawText(line, w / 2f, y, stroke)
            canvas.drawText(line, w / 2f, y, fill)
            y += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.lines()) {
            var line = ""
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    out.add(line)
                    line = word
                } else line = candidate
            }
            if (line.isNotEmpty()) out.add(line)
        }
        return out.ifEmpty { listOf(text) }
    }

    /** A [Frame] placed on the full sticker canvas, for previews and static stickers. */
    fun toCanvas(frame: Frame, size: Int = SIZE): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(frame.bitmap, frame.rect.left.toFloat(), frame.rect.top.toFloat(), null)
        return out
    }

    /**
     * A finished image (a text style, say) made into a sticker: fitted on the canvas. Opaque
     * images get round corners so WhatsApp sees the transparency and treats it as a sticker.
     */
    fun fromImage(image: Bitmap): ByteArray {
        val opaque = !hasTransparency(image)
        return encodeStatic(toCanvas(draw(image, Shape.FULL, null, SIZE, opaqueCard = opaque)), preferLossless = true)
    }

    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val w = bitmap.width
        val h = bitmap.height
        val points = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1, w / 2 to 0, w / 2 to h - 1, 0 to h / 2, w - 1 to h / 2)
        return points.any { (x, y) -> Color.alpha(bitmap.getPixel(x, y)) < 250 }
    }

    // ----------------------------------------------------------------- encoding

    @Suppress("DEPRECATION")
    private fun compress(bitmap: Bitmap, quality: Int, lossless: Boolean): ByteArray {
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(format, if (lossless) 100 else quality, out)
        return out.toByteArray()
    }

    /** A static 512×512 sticker under 100 KB: lossless when it fits (sharp text), else lossy. */
    fun encodeStatic(canvasBitmap: Bitmap, preferLossless: Boolean = false): ByteArray {
        if (preferLossless) {
            val lossless = withExif(compress(canvasBitmap, 100, true), canvasBitmap.width, canvasBitmap.height)
            if (lossless.size <= MAX_STATIC_BYTES) return lossless
        }
        var best: ByteArray? = null
        for (q in intArrayOf(90, 80, 70, 60, 50, 40, 30, 20)) {
            best = withExif(compress(canvasBitmap, q, false), canvasBitmap.width, canvasBitmap.height)
            if (best.size <= MAX_STATIC_BYTES) break
        }
        return best!!
    }

    /**
     * An animated sticker from [frames] shown [frameMs] each, looping. Quality goes down (and
     * then frames are dropped) until it fits in WhatsApp's 500 KB.
     */
    fun encodeAnimated(frames: List<Frame>, frameMs: Int, progress: (Float) -> Unit = {}): ByteArray {
        require(frames.isNotEmpty())
        var list = frames
        var duration = frameMs
        val qualities = intArrayOf(75, 62, 50, 40, 32, 25, 18)

        // Guess a good quality from a few frames, so most stickers are encoded only once. If even
        // a low quality won't fit, use fewer frames (each shown longer) rather than a blurry mess.
        val sample = list.filterIndexed { i, _ -> i % max(1, list.size / 6) == 0 }.take(6)
        val avgSize = HashMap<Int, Double>()
        fun avg(q: Int) = avgSize.getOrPut(q) { sample.sumOf { encodeFrame(it, q).size }.toDouble() / sample.size }
        val floor = 5 // index of the lowest quality worth keeping all frames for
        var qi = qualities.indexOfFirst { q -> avg(q) * list.size < MAX_ANIMATED_BYTES * 0.92 }
        while ((qi < 0 || qi > floor) && list.size > 8 && avg(qualities[floor]) * list.size > MAX_ANIMATED_BYTES * 0.92) {
            list = list.filterIndexed { i, _ -> i % 2 == 0 }
            duration *= 2
            qi = qualities.indexOfFirst { q -> avg(q) * list.size < MAX_ANIMATED_BYTES * 0.92 }
        }
        if (qi < 0) qi = qualities.size - 1
        Log.i("FMAboardSticker", "Animated: ${list.size} frames of $duration ms at quality ${qualities[qi]}")

        var attempt = 0
        while (true) {
            val q = qualities[qi]
            val data = ArrayList<ByteArray>(list.size)
            list.forEachIndexed { i, f ->
                data.add(encodeFrame(f, q))
                progress(((attempt + (i + 1f) / list.size) / (attempt + 1.5f)).coerceAtMost(0.98f))
            }
            val webp = muxAnimated(list, data, duration)
            if (webp.size <= MAX_ANIMATED_BYTES) return webp
            attempt++
            if (qi < qualities.size - 1) {
                qi++
            } else if (list.size > 4) {
                // Lowest quality and still too big: half the frames, each shown twice as long
                list = list.filterIndexed { i, _ -> i % 2 == 0 }
                duration *= 2
                qi = 3
            } else return webp
        }
    }

    private fun encodeFrame(frame: Frame, quality: Int): ByteArray = compress(frame.bitmap, quality, false)

    private class Chunk(val fourcc: String, val data: ByteArray)

    private fun le32(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)

    private fun parse(webp: ByteArray): List<Chunk> {
        require(webp.size >= 12 && String(webp, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(webp, 8, 4, Charsets.US_ASCII) == "WEBP") { "Not a WebP" }
        val chunks = ArrayList<Chunk>()
        var p = 12
        while (p + 8 <= webp.size) {
            val fourcc = String(webp, p, 4, Charsets.US_ASCII)
            val size = le32(webp, p + 4)
            if (size < 0 || p + 8 + size > webp.size) break
            chunks.add(Chunk(fourcc, webp.copyOfRange(p + 8, p + 8 + size)))
            p += 8 + size + (size and 1)
        }
        return chunks
    }

    /** The image bitstream chunks (ALPH + VP8, or VP8L) of a single-frame WebP. */
    private fun imageChunks(webp: ByteArray) = parse(webp).filter { it.fourcc == "ALPH" || it.fourcc == "VP8 " || it.fourcc == "VP8L" }

    private class Writer {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u24(v: Int) { u8(v); u8(v shr 8); u8(v shr 16) }
        fun u32(v: Int) { u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24) }
        fun bytes(b: ByteArray) = out.write(b)
        fun chunk(fourcc: String, data: ByteArray) {
            bytes(fourcc.toByteArray(Charsets.US_ASCII))
            u32(data.size)
            bytes(data)
            if (data.size and 1 == 1) u8(0)
        }
        fun chunk(c: Chunk) = chunk(c.fourcc, c.data)
    }

    private fun vp8x(flags: Int, width: Int, height: Int): ByteArray = Writer().apply {
        u8(flags); u24(0); u24(width - 1); u24(height - 1)
    }.out.toByteArray()

    private fun riff(body: ByteArray): ByteArray = Writer().apply {
        bytes("RIFF".toByteArray(Charsets.US_ASCII))
        u32(4 + body.size)
        bytes("WEBP".toByteArray(Charsets.US_ASCII))
        bytes(body)
    }.out.toByteArray()

    private const val FLAG_ANIMATION = 0x02
    private const val FLAG_EXIF = 0x08
    private const val FLAG_ALPHA = 0x10

    private fun withExif(single: ByteArray, width: Int, height: Int): ByteArray {
        val body = Writer().apply {
            chunk("VP8X", vp8x(FLAG_ALPHA or FLAG_EXIF, width, height))
            imageChunks(single).forEach { chunk(it) }
            chunk("EXIF", exif())
        }
        return riff(body.out.toByteArray())
    }

    private fun muxAnimated(frames: List<Frame>, data: List<ByteArray>, frameMs: Int): ByteArray {
        val body = Writer().apply {
            chunk("VP8X", vp8x(FLAG_ANIMATION or FLAG_ALPHA or FLAG_EXIF, SIZE, SIZE))
            chunk("ANIM", Writer().apply { u32(0); u16(0) }.out.toByteArray()) // transparent, loop forever
            frames.forEachIndexed { i, f ->
                val anmf = Writer().apply {
                    u24(f.rect.left / 2); u24(f.rect.top / 2)
                    u24(f.rect.width() - 1); u24(f.rect.height() - 1)
                    u24(frameMs)
                    u8(0x02) // do not blend: each frame replaces its area
                    imageChunks(data[i]).forEach { chunk(it) }
                }
                chunk("ANMF", anmf.out.toByteArray())
            }
            chunk("EXIF", exif())
        }
        return riff(body.out.toByteArray())
    }

    /** The sticker metadata WhatsApp reads: pack name and author ("FMAboard"). */
    private fun exif(): ByteArray {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = ("{\"sticker-pack-id\":\"${esc(PACK_ID)}\",\"sticker-pack-name\":\"${esc(PACK_NAME)}\"," +
            "\"sticker-pack-publisher\":\"${esc(PUBLISHER)}\",\"emojis\":[\"\"]}").toByteArray(Charsets.UTF_8)
        return Writer().apply {
            // Little-endian TIFF header with one IFD entry (tag 0x5741, type UNDEFINED) holding the JSON
            bytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x41, 0x57, 0x07, 0x00))
            u32(json.size)
            u32(0x16)
            bytes(json)
        }.out.toByteArray()
    }
}
