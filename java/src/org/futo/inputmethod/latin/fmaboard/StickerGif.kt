package org.futo.inputmethod.latin.fmaboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.ByteArrayOutputStream

/**
 * Turns one of our animated WebP stickers into a looping GIF with a transparent background, for
 * apps that play GIFs but not animated WebP (Instagram). Only reads the WebP that
 * [StickerMaker] writes: frames that replace their area, no blending.
 */
object StickerGif {
    private class AnimFrame(val x: Int, val y: Int, val w: Int, val h: Int, val ms: Int, val image: ByteArray)

    private fun le24(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16)

    private fun le32(b: ByteArray, p: Int) = le24(b, p) or ((b[p + 3].toInt() and 0xff) shl 24)

    private fun chunks(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            val size = le32(b, p + 4)
            if (size < 0 || p + 8 + size > to) break
            out.add(Triple(String(b, p, 4, Charsets.US_ASCII), p + 8, size))
            p += 8 + size + (size and 1)
        }
        return out
    }

    /** True if [webp] is an animated WebP. */
    fun isAnimated(webp: ByteArray): Boolean =
        webp.size > 30 && String(webp, 12, 4, Charsets.US_ASCII) == "VP8X" && (webp[20].toInt() and 0x02) != 0

    private fun frames(webp: ByteArray): List<AnimFrame> =
        chunks(webp, 12, webp.size).filter { it.first == "ANMF" }.map { (_, p, size) ->
            val inner = chunks(webp, p + 16, p + size)
            val w = le24(webp, p + 6) + 1
            val h = le24(webp, p + 9) + 1
            // Rebuild the frame as a still WebP that BitmapFactory can read
            val body = ByteArrayOutputStream()
            fun put32(v: Int) { for (i in 0 until 4) body.write(v shr (8 * i)) }
            fun put24(v: Int) { for (i in 0 until 3) body.write(v shr (8 * i)) }
            if (inner.any { it.first == "ALPH" }) {
                body.write("VP8X".toByteArray(Charsets.US_ASCII)); put32(10)
                body.write(0x10); put24(0); put24(w - 1); put24(h - 1)
            }
            for ((fourcc, cp, cs) in inner) {
                if (fourcc != "ALPH" && fourcc != "VP8 " && fourcc != "VP8L") continue
                body.write(fourcc.toByteArray(Charsets.US_ASCII)); put32(cs)
                body.write(webp, cp, cs)
                if (cs and 1 == 1) body.write(0)
            }
            val riff = ByteArrayOutputStream()
            riff.write("RIFF".toByteArray(Charsets.US_ASCII))
            val len = 4 + body.size()
            for (i in 0 until 4) riff.write(len shr (8 * i))
            riff.write("WEBP".toByteArray(Charsets.US_ASCII))
            body.writeTo(riff)
            AnimFrame(le24(webp, p) * 2, le24(webp, p + 3) * 2, w, h, le24(webp, p + 12), riff.toByteArray())
        }

    /** The GIF, or null if [webp] is not an animation we can read. */
    fun fromAnimatedWebp(webp: ByteArray): ByteArray? {
        if (!isAnimated(webp)) return null
        val width = le24(webp, 24) + 1
        val height = le24(webp, 27) + 1
        val list = frames(webp)
        if (list.isEmpty()) return null

        // Draw each frame onto the canvas the way a WebP player would
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val pixels = ArrayList<IntArray>(list.size)
        for (f in list) {
            val bmp = BitmapFactory.decodeByteArray(f.image, 0, f.image.size) ?: return null
            canvas.drawRect(f.x.toFloat(), f.y.toFloat(), (f.x + f.w).toFloat(), (f.y + f.h).toFloat(), clear)
            canvas.drawBitmap(bmp, f.x.toFloat(), f.y.toFloat(), null)
            bmp.recycle()
            pixels.add(IntArray(width * height).also { canvasBitmap.getPixels(it, 0, width, 0, 0, width, height) })
        }
        canvasBitmap.recycle()

        val palette = palette(pixels)
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width); u16(height); u8(0xF7); u8(0); u8(0)
        for (c in palette) { u8(c shr 16); u8(c shr 8); u8(c) }
        // Loop forever
        u8(0x21); u8(0xFF); u8(11); out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); u8(3); u8(1); u16(0); u8(0)

        val nearest = IntArray(1 shl 15) { -1 }
        val indices = ByteArray(width * height)
        list.forEachIndexed { i, f ->
            val px = pixels[i]
            for (j in px.indices) {
                val c = px[j]
                indices[j] = if ((c ushr 24) < 128) 0 else {
                    val key = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)
                    var n = nearest[key]
                    if (n < 0) { n = closest(palette, c); nearest[key] = n }
                    n.toByte()
                }
            }
            // Graphic control: clear to transparent after each frame, index 0 is transparent
            u8(0x21); u8(0xF9); u8(4); u8((2 shl 2) or 1); u16((f.ms + 5) / 10); u8(0); u8(0)
            u8(0x2C); u16(0); u16(0); u16(width); u16(height); u8(0)
            u8(8)
            lzw(indices, out)
            u8(0)
        }
        u8(0x3B)
        return out.toByteArray()
    }

    /** Index 0 is transparent; the other 255 are the most used colours (5 bits per channel). */
    private fun palette(frames: List<IntArray>): IntArray {
        val count = IntArray(1 shl 15)
        val r = LongArray(1 shl 15); val g = LongArray(1 shl 15); val b = LongArray(1 shl 15)
        for (px in frames) {
            var j = 0
            while (j < px.size) {
                val c = px[j]
                if ((c ushr 24) >= 128) {
                    val key = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)
                    count[key]++
                    r[key] += (c shr 16) and 0xff; g[key] += (c shr 8) and 0xff; b[key] += c and 0xff
                }
                j += 2
            }
        }
        val top = count.indices.filter { count[it] > 0 }.sortedByDescending { count[it] }.take(255)
        val palette = IntArray(256)
        top.forEachIndexed { i, k ->
            val n = count[k]
            palette[i + 1] = ((r[k] / n).toInt() shl 16) or ((g[k] / n).toInt() shl 8) or (b[k] / n).toInt()
        }
        // Unused entries repeat a real colour so nothing maps to them by accident
        for (i in top.size + 1 until 256) palette[i] = palette[if (top.isEmpty()) 0 else 1]
        return palette
    }

    private fun closest(palette: IntArray, c: Int): Int {
        val cr = (c shr 16) and 0xff; val cg = (c shr 8) and 0xff; val cb = c and 0xff
        var best = 1; var bestD = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val p = palette[i]
            val dr = ((p shr 16) and 0xff) - cr; val dg = ((p shr 8) and 0xff) - cg; val db = (p and 0xff) - cb
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** GIF LZW with 8-bit codes, written as sub-blocks. */
    private fun lzw(data: ByteArray, out: ByteArrayOutputStream) {
        val clearCode = 256
        val eoi = 257
        var codeSize = 9
        var next = 258
        val table = HashMap<Int, Int>(8192)
        val block = ByteArray(255)
        var blockLen = 0
        var acc = 0
        var bits = 0
        fun flushBlock() {
            if (blockLen == 0) return
            out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0
        }
        fun emit(code: Int) {
            acc = acc or (code shl bits)
            bits += codeSize
            while (bits >= 8) {
                block[blockLen++] = acc.toByte()
                if (blockLen == 255) flushBlock()
                acc = acc ushr 8
                bits -= 8
            }
        }
        emit(clearCode)
        var prefix = data[0].toInt() and 0xff
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xff
            val key = (prefix shl 8) or k
            val code = table[key]
            if (code != null) { prefix = code; continue }
            emit(prefix)
            if (next == 4096) {
                emit(clearCode)
                table.clear(); next = 258; codeSize = 9
            } else {
                if (next >= (1 shl codeSize)) codeSize++
                table[key] = next++
            }
            prefix = k
        }
        emit(prefix)
        emit(eoi)
        if (bits > 0) { block[blockLen++] = acc.toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
    }
}
