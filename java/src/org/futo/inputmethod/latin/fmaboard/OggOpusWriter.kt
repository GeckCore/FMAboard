package org.futo.inputmethod.latin.fmaboard

import java.io.File
import java.io.OutputStream
import kotlin.random.Random

/**
 * Writes Opus packets into an Ogg file the way voice notes are made (RFC 7845): OpusHead and
 * OpusTags on pages of their own, then the audio with granule positions counted at 48 kHz.
 * Android's own Ogg muxer makes files that play on this phone but that WhatsApp on other phones
 * refuses ("algo falló con el archivo de audio"), so the file is put together here instead.
 */
internal class OggOpusWriter(file: File, private val preSkip: Int, private val inputRate: Int) {
    private val out: OutputStream = file.outputStream().buffered()
    private val serial = Random.nextInt()
    private var sequence = 0
    private var granule = 0L

    private val pagePackets = ArrayList<ByteArray>()
    private var pageSegments = 0
    private var pageSamples = 0

    init {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head)
        head[8] = 1 // version
        head[9] = 1 // mono
        putLe(head, 10, preSkip.toLong(), 2)
        putLe(head, 12, inputRate.toLong(), 4)
        // output gain 0, mapping family 0
        writePage(listOf(head), 0, flags = 0x02)

        val vendor = "FMAboard".toByteArray(Charsets.UTF_8)
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(tags)
        putLe(tags, 8, vendor.size.toLong(), 4)
        vendor.copyInto(tags, 12)
        writePage(listOf(tags), 0, flags = 0)
        granule = 0
    }

    /** Adds one Opus packet as it comes out of the encoder. */
    fun write(packet: ByteArray) {
        val segments = packet.size / 255 + 1
        // A page holds up to 255 lacing values; about a second per page keeps seeking easy
        if (pagePackets.isNotEmpty() && (pageSegments + segments > 255 || pageSamples >= 48_000)) flush(eos = false)
        pagePackets += packet
        pageSegments += segments
        pageSamples += samplesIn(packet)
    }

    /** [totalSamples48k] is the real length of the audio, to cut the encoder's padding at the end. */
    fun finish(totalSamples48k: Long) {
        flush(eos = true, lastGranule = preSkip + totalSamples48k)
        out.close()
    }

    private fun flush(eos: Boolean, lastGranule: Long = -1) {
        granule += pageSamples
        val g = if (eos && lastGranule in 1..granule) lastGranule else granule
        writePage(pagePackets, g, flags = if (eos) 0x04 else 0)
        pagePackets.clear()
        pageSegments = 0
        pageSamples = 0
    }

    private fun writePage(packets: List<ByteArray>, granulePos: Long, flags: Int) {
        val lacing = ArrayList<Int>()
        for (p in packets) {
            var left = p.size
            while (left >= 255) { lacing += 255; left -= 255 }
            lacing += left
        }
        val header = ByteArray(27 + lacing.size)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = 0
        header[5] = flags.toByte()
        putLe(header, 6, granulePos, 8)
        putLe(header, 14, serial.toLong(), 4)
        putLe(header, 18, sequence++.toLong(), 4)
        // CRC (22..25) filled below
        header[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> header[27 + i] = v.toByte() }

        var crc = crcUpdate(0, header, 0, header.size)
        for (p in packets) crc = crcUpdate(crc, p, 0, p.size)
        putLe(header, 22, crc.toLong() and 0xffffffffL, 4)

        out.write(header)
        for (p in packets) out.write(p)
    }

    companion object {
        private val CRC_TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        private fun crcUpdate(crc: Int, data: ByteArray, from: Int, len: Int): Int {
            var c = crc
            for (i in from until from + len) {
                c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (data[i].toInt() and 0xff)) and 0xff]
            }
            return c
        }

        private fun putLe(dst: ByteArray, at: Int, value: Long, bytes: Int) {
            for (i in 0 until bytes) dst[at + i] = (value ushr (8 * i)).toByte()
        }

        /** How many 48 kHz samples an Opus packet holds, from its first bytes (RFC 6716 §3.1). */
        fun samplesIn(packet: ByteArray): Int {
            if (packet.isEmpty()) return 0
            val toc = packet[0].toInt() and 0xff
            val config = toc shr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                config < 16 -> intArrayOf(480, 960)[config and 1]
                else -> intArrayOf(120, 240, 480, 960)[config and 3]
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size > 1) packet[1].toInt() and 0x3f else 0
            }
            return frame * frames
        }
    }
}
