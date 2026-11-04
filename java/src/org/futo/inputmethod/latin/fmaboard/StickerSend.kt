package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.LatinIME
import java.io.File

/**
 * A finished sticker waiting to be sent: the keyboard puts it into the chat the next time
 * that app's text field opens (the same way "Texto de imagen" types its text).
 */
object StickerSend {
    const val EXTRA_TARGET_PACKAGE = "fmaboard_target_package"
    private const val TAG = "FMAboardSticker"
    private const val PENDING_MS = 10 * 60 * 1000L
    const val MIME_STICKER = "image/webp.wasticker"

    private class Pending(val file: File, val pkg: String?, val time: Long)
    @Volatile private var pending: Pending? = null

    fun launch(context: Context, targetPackage: String?) {
        context.startActivity(Intent(context, StickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage))
    }

    /** Writes a finished sticker to the cache (old ones are cleared out). */
    fun save(context: Context, webp: ByteArray): File {
        val dir = File(context.cacheDir, "stickers").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (it.name.startsWith("sticker-") && now - it.lastModified() > 24 * 3600_000L) it.delete() }
        return File(dir, "sticker-$now.webp").also { it.writeBytes(webp) }
    }

    fun setPending(file: File, targetPackage: String?) {
        pending = Pending(file, targetPackage, System.currentTimeMillis())
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fmaboard.files", file)

    /** The type to send a sticker as in a field that takes these [mimeTypes]; null if none fits. */
    fun mimeFor(supports: (String) -> Boolean): String? = when {
        supports(MIME_STICKER) -> MIME_STICKER
        supports("image/webp") -> "image/webp"
        else -> null
    }

    private fun supportsFromEditor(info: EditorInfo?): (String) -> Boolean {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) info?.contentMimeTypes else null
        return { wanted ->
            types?.any { t -> t == wanted || (t.endsWith("/*") && wanted.startsWith(t.dropLast(1))) || t == "*/*" } == true
        }
    }

    /** Called when a field opens: sends the waiting sticker if this is the app it is for. */
    @JvmStatic
    fun onStartField(ime: LatinIME, info: EditorInfo?) {
        val p = pending ?: return
        if (System.currentTimeMillis() - p.time > PENDING_MS || !p.file.exists()) { pending = null; return }
        if (p.pkg != null && p.pkg != info?.packageName) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Log.i(TAG, "Field of ${info?.packageName} takes: ${info?.contentMimeTypes?.joinToString()}")
        }
        // A search box or similar: keep it for the chat's own field
        val mime = mimeFor(supportsFromEditor(info)) ?: return
        pending = null
        ime.lifecycleScope.launch {
            delay(200)
            ime.uixManager.keyboardManagerForAction.typeUri(uriFor(ime, p.file), listOf(mime))
        }
    }
}

