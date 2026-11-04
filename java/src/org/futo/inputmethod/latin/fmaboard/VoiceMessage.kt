package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.futo.inputmethod.latin.uix.PreferenceUtils
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Text to a voice message, on the phone: Kokoro-82M (Apache 2.0) run by sherpa-onnx, with the
 * Spanish voices it ships. The text is turned into phonemes by [SpanishPhonemizer]. The model
 * (~136 MB) is downloaded once, the first time the user asks for it; after that it works offline
 * and nothing leaves the phone.
 */
object VoiceMessage {
    private const val TAG = "FMAboardVoice"

    enum class Voice(val label: String, val sid: Int) {
        Dora("Dora", 28),
        Alex("Alex", 29),
    }

    private class ModelFile(val name: String, val sha256: String, val size: Long)

    // Pinned revision of the sherpa-onnx export of Kokoro v1.0 (int8), so the files never change.
    private const val BASE_URL = "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/" +
        "57adb37fc0e8d4bd430cfc1b8f8c2294945b9af2/"
    private val FILES = listOf(
        ModelFile("model.int8.onnx", "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e", 114_202_993),
        ModelFile("voices.bin", "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29", 28_200_960),
        ModelFile("tokens.txt", "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425", 687),
        ModelFile("LICENSE", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", 11_358),
    )
    val downloadBytes: Long = FILES.sumOf { it.size }

    const val PREF_VOICE = "fmaboard_tts_voice"
    private const val MAX_CHUNK_CHARS = 380 // phonemes per piece; Kokoro takes at most ~510 tokens

    private val mutex = Mutex()
    private var tts: OfflineTts? = null
    @Volatile private var stopRequested = false

    private fun modelDir(context: Context) = File(context.filesDir, "tts/kokoro-es")

    fun isInstalled(context: Context): Boolean =
        FILES.all { File(modelDir(context), it.name).length() == it.size }

    fun installedBytes(context: Context): Long =
        modelDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun voice(context: Context): Voice {
        val name = PreferenceUtils.getDefaultSharedPreferences(context).getString(PREF_VOICE, null)
        return Voice.entries.firstOrNull { it.name == name } ?: Voice.Dora
    }

    fun setVoice(context: Context, voice: Voice) {
        PreferenceUtils.getDefaultSharedPreferences(context).edit { putString(PREF_VOICE, voice.name) }
    }

    class VoiceException(message: String) : Exception(message)

    /** Blocking; resumes a partial download. [progress] gets (bytes done, bytes total). */
    fun download(context: Context, isCancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val dir = modelDir(context).apply { mkdirs() }
        var done = FILES.sumOf { f -> File(dir, f.name).takeIf { it.length() == f.size }?.length() ?: 0L }
        for (f in FILES) {
            val target = File(dir, f.name)
            if (target.length() == f.size) continue
            val part = File(dir, f.name + ".part")
            if (part.length() > f.size) part.delete()
            val connection = (URL(BASE_URL + f.name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            try {
                val code = connection.responseCode
                if (code != 200 && code != 206) throw VoiceException("No se pudo descargar la voz (error $code)")
                if (code == 200) part.delete() // the server ignored the range: start over
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(part.length())
                    var fileDone = part.length()
                    done += fileDone
                    progress(done, downloadBytes)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            if (isCancelled()) throw CancellationException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            fileDone += n
                            done += n
                            progress(done, downloadBytes)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (sha256(part) != f.sha256) {
                part.delete()
                throw VoiceException("La descarga de la voz llegó dañada. Vuelve a intentarlo.")
            }
            target.delete()
            if (!part.renameTo(target)) throw VoiceException("No se pudo guardar la voz")
        }
    }

    suspend fun deleteModel(context: Context) = mutex.withLock {
        tts?.release()
        tts = null
        modelDir(context).deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun engine(context: Context): OfflineTts {
        tts?.let { return it }
        val dir = modelDir(context).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.int8.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    lang = "es",
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ),
        )
        return OfflineTts(config = config).also { tts = it }
    }

    /** Frees the model's memory (~200 MB). The next message loads it again. */
    suspend fun release() = mutex.withLock {
        tts?.release()
        tts = null
    }

    fun stop() {
        stopRequested = true
    }

    fun isStopRequested() = stopRequested

    /** Sentences (split further at commas when long), each as phoneme codepoints. */
    internal fun phonemeChunks(text: String): Array<IntArray> {
        val pieces = mutableListOf<String>()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+|\\n+"))) {
            val ipa = SpanishPhonemizer.phonemize(sentence).trim()
            if (ipa.none { it.isLetter() }) continue
            if (ipa.length <= MAX_CHUNK_CHARS) { pieces += ipa; continue }
            val current = StringBuilder()
            for (part in ipa.split(Regex("(?<=[,;:])\\s+|\\s+"))) {
                if (current.isNotEmpty() && current.length + part.length + 1 > MAX_CHUNK_CHARS) {
                    pieces += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
            if (current.isNotEmpty()) pieces += current.toString()
        }
        return pieces.map { p -> p.codePoints().toArray() }.toTypedArray()
    }

    class Audio(val samples: FloatArray, val sampleRate: Int)

    /** Blocking and slow (about as long as the audio): call from a background thread. */
    suspend fun synthesize(context: Context, text: String, voice: Voice, speed: Float = 1.0f): Audio =
        mutex.withLock {
            val chunks = phonemeChunks(text)
            if (chunks.isEmpty()) throw VoiceException("No hay nada que leer en voz alta")
            stopRequested = false
            val started = System.currentTimeMillis()
            val engine = try {
                engine(context)
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the voice model", e)
                throw VoiceException("No se pudo cargar la voz. Prueba a borrarla en Ajustes y descargarla otra vez.")
            }
            val config = GenerationConfig(sid = voice.sid, speed = speed, phonemeCodepoints = chunks)
            val audio = engine.generateWithConfigAndCallback("", config, StopCallback())
            if (stopRequested) throw CancellationException()
            if (audio.samples.isEmpty()) throw VoiceException("No se pudo generar el audio")
            Log.i(TAG, "Generated %.1f s of audio in %d ms".format(
                audio.samples.size.toFloat() / audio.sampleRate, System.currentTimeMillis() - started))
            Audio(audio.samples, audio.sampleRate)
        }

    /** Writes the audio as a small file chat apps accept: Ogg Opus (like voice notes), else M4A. */
    fun encode(context: Context, audio: Audio): File {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ogg = File(dir, "mensaje-$stamp.ogg")
            try {
                encodeOpus(audio, ogg)
                return ogg
            } catch (e: Exception) {
                Log.w(TAG, "Opus encoding failed, using AAC", e)
                ogg.delete()
            }
        }
        val m4a = File(dir, "mensaje-$stamp.m4a")
        encodeAac(audio, m4a)
        return m4a
    }

    fun mimeOf(file: File) = if (file.extension == "ogg") "audio/ogg" else "audio/mp4"

    /** Opus at 48 kHz (what voice notes are decoded at), put in an Ogg file by [OggOpusWriter]. */
    private fun encodeOpus(audio: Audio, out: File) {
        val rate = 48_000
        val samples = resample(audio.samples, audio.sampleRate, rate)
        var writer: OggOpusWriter? = null
        try {
            runEncoder(samples, rate, MediaFormat.MIMETYPE_AUDIO_OPUS, 32_000,
                onFormat = { format ->
                    // csd-1 is the encoder's delay in nanoseconds: the decoder skips that much
                    val delayNs = format.getByteBuffer("csd-1")
                        ?.takeIf { it.remaining() >= 8 }?.order(ByteOrder.LITTLE_ENDIAN)?.getLong(0)
                    val preSkip = delayNs?.let { (it * rate / 1_000_000_000L).toInt() }
                        ?.takeIf { it in 0..10_000 } ?: 312
                    writer = OggOpusWriter(out, preSkip, rate)
                },
                onPacket = { packet -> writer?.write(packet) })
            val w = writer ?: throw VoiceException("No se pudo crear el audio")
            w.finish(samples.size.toLong())
            writer = null
        } finally {
            writer?.let { try { it.finish(samples.size.toLong()) } catch (_: Exception) {} }
        }
        if (out.length() < 100) throw VoiceException("No se pudo crear el audio")
    }

    private fun encodeAac(audio: Audio, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var samplesOut = 0L
        try {
            runEncoder(audio.samples, audio.sampleRate, MediaFormat.MIMETYPE_AUDIO_AAC, 64_000,
                onFormat = { format -> track = muxer.addTrack(format); muxer.start() },
                onPacket = { packet ->
                    info.set(0, packet.size, samplesOut * 1_000_000L / audio.sampleRate, 0)
                    muxer.writeSampleData(track, ByteBuffer.wrap(packet), info)
                    samplesOut += 1024
                })
        } finally {
            try { if (track >= 0) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
        if (track < 0 || out.length() == 0L) throw VoiceException("No se pudo crear el audio")
    }

    /** Linear resampling; plenty for a voice going from 24 to 48 kHz. */
    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(n) { i ->
            val pos = i * step
            val a = pos.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val t = (pos - a).toFloat()
            src[a] * (1 - t) + src[b] * t
        }
    }

    private fun runEncoder(samples: FloatArray, sampleRate: Int, mime: String, bitRate: Int,
                           onFormat: (MediaFormat) -> Unit, onPacket: (ByteArray) -> Unit) {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        pcm.flip()

        val codec = MediaCodec.createEncoderByType(mime)
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var samplesQueued = 0L
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val n = minOf(buffer.remaining(), pcm.remaining()) and 1.inv()
                        val timeUs = samplesQueued * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val slice = pcm.slice().apply { limit(n) }
                            buffer.put(slice)
                            pcm.position(pcm.position() + n)
                            codec.queueInputBuffer(index, 0, n, timeUs, 0)
                            samplesQueued += n / 2
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onFormat(codec.outputFormat)
                        started = true
                    }
                    outIndex >= 0 -> {
                        val data = codec.getOutputBuffer(outIndex)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && started) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            onPacket(ByteArray(info.size).also { data.get(it) })
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        if (!started) throw VoiceException("No se pudo crear el audio")
    }
}

/**
 * Called by sherpa-onnx's native code after each piece of audio, by its exact method signature
 * (invoke([F)Ljava/lang/Integer;). A lambda gets merged and renamed by R8, so this is a named class
 * kept in proguard-rules.pro. Returning 0 stops the generation.
 */
class StopCallback : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (VoiceMessage.isStopRequested()) 0 else 1
}
