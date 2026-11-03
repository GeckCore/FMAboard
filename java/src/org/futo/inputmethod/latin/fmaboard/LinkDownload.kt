package org.futo.inputmethod.latin.fmaboard

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.PreferenceUtils
import java.io.File

/**
 * Downloads a video or its audio from a link (TikTok, YouTube, Instagram, X…) with yt-dlp,
 * which runs on the phone as a separate process. One download at a time; it keeps going if
 * the download screen is closed.
 */
object LinkDownload {
    sealed class State {
        object Idle : State()
        class Working(val message: String, val progress: Float = -1f) : State()
        class Done(val file: File, val mime: String, val title: String, val audio: Boolean) : State()
        class Failed(val message: String) : State()
    }

    const val EXTRA_URL = "fmaboard_url"
    const val EXTRA_TARGET_PACKAGE = "fmaboard_target_package"

    private const val PROCESS_ID = "fmaboard_download"
    private const val PREF_LAST_UPDATE = "fmaboard_ytdlp_last_update"
    private const val UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    // Up to 720p, H.264 + AAC when available so WhatsApp plays it without converting
    private const val VIDEO_FORMAT =
        "bv*[height<=720][vcodec^=avc]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b[height<=720]/b"

    private val URL = Regex("https?://[^\\s]+")

    val state = MutableStateFlow<State>(State.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var initialized = false

    fun findUrl(text: CharSequence?): String? =
        text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', '!', '?', '"', '\'') }

    fun launch(context: Context, targetPackage: String?, url: String?) {
        context.startActivity(Intent(context, LinkDownloadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage))
    }

    fun isWorking() = state.value is State.Working

    fun start(context: Context, url: String, audio: Boolean, forceUpdate: Boolean = false) {
        if (isWorking()) return
        val app = context.applicationContext
        state.value = State.Working("Preparando el descargador…")
        job = scope.launch {
            try {
                ensureReady(app, forceUpdate)
                cleanOld(app)
                val dir = File(app.cacheDir, "downloads/${System.currentTimeMillis()}").apply { mkdirs() }
                state.value = State.Working("Buscando el ${if (audio) "audio" else "vídeo"}…")
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-o", dir.absolutePath + "/%(title).60B.%(ext)s")
                    if (audio) {
                        addOption("-f", "ba[ext=m4a]/ba/b")
                        addOption("-x")
                        addOption("--audio-format", "m4a")
                    } else {
                        addOption("-f", VIDEO_FORMAT)
                        addOption("--merge-output-format", "mp4")
                    }
                }
                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, _ ->
                    if (progress <= 0f) return@execute
                    state.value = if (progress >= 100f) {
                        State.Working("Terminando…", 1f)
                    } else {
                        State.Working("Descargando… ${progress.toInt()}%" +
                            if (eta > 0) " (quedan ${eta}s)" else "", progress / 100f)
                    }
                }
                val file = dir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException("No se descargó ningún archivo")
                state.value = State.Done(file, mimeFor(file, audio), file.nameWithoutExtension, audio)
            } catch (e: YoutubeDL.CanceledException) {
                state.value = State.Idle
            } catch (e: InterruptedException) {
                state.value = State.Idle
            } catch (e: Throwable) {
                // Never let a downloader problem take the keyboard down
                state.value = State.Failed(describe(e))
            }
        }
    }

    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        job?.cancel()
        state.value = State.Idle
    }

    fun reset() {
        if (!isWorking()) state.value = State.Idle
    }

    /** Unpacks python/yt-dlp the first time, and keeps yt-dlp fresh (sites change often). */
    private fun ensureReady(context: Context, forceUpdate: Boolean) {
        if (!initialized) {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized = true
        }
        val prefs = PreferenceUtils.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()
        if (forceUpdate || now - prefs.getLong(PREF_LAST_UPDATE, 0L) > UPDATE_EVERY_MS) {
            state.value = State.Working("Actualizando el descargador…")
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong(PREF_LAST_UPDATE, now).apply()
            } catch (e: Exception) {
                // Offline or GitHub unreachable: try with the version we have
            }
        }
    }

    private fun cleanOld(context: Context) {
        val now = System.currentTimeMillis()
        File(context.cacheDir, "downloads").listFiles()
            ?.filter { now - it.lastModified() > KEEP_MS }
            ?.forEach { it.deleteRecursively() }
    }

    private fun mimeFor(file: File, audio: Boolean): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (audio) "audio/mp4" else "video/mp4"

    private fun describe(e: Throwable): String {
        val text = e.message ?: return "Error al descargar"
        val error = text.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: text.trim()
        return when {
            error.contains("Unsupported URL", true) -> "Ese enlace no es de una web compatible"
            error.contains("login", true) || error.contains("cookies", true) || error.contains("private", true) ->
                "Ese contenido es privado o necesita iniciar sesión"
            error.contains("Unable to download", true) || error.contains("resolve", true) ->
                "No se pudo conectar. ¿Hay internet?"
            else -> error.take(300)
        }
    }

    /** Copies the file to Movies/FMAboard or Music/FMAboard so it shows in the gallery. */
    fun saveToGallery(context: Context, done: State.Done): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val collection = if (done.audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, done.file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, done.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (done.audio) "Music/FMAboard" else "Movies/FMAboard")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> done.file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}

/** Takes the link from the clipboard (or from the text being written) and opens the download screen. */
val LinkDownloadAction = Action(
    icon = R.drawable.fmaboard_download,
    name = R.string.fmaboard_download_title,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val ime = manager.getLatinIMEForDebug()
        val clip = try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(context)
        } catch (e: Exception) { null }
        val url = LinkDownload.findUrl(clip)
            ?: LinkDownload.findUrl(ime.currentInputConnection?.getTextBeforeCursor(2000, 0))
        LinkDownload.launch(context, ime.currentInputEditorInfo?.packageName, url)
    },
    persistentState = null,
    windowImpl = null,
)
