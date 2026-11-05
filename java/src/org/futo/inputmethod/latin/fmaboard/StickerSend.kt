package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
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

    /** Opens Crear sticker; with [sourcePath] (a file of ours, e.g. a clipboard image) it starts from it. */
    fun launch(context: Context, targetPackage: String?, sourcePath: String? = null) {
        context.startActivity(Intent(context, StickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_SOURCE_PATH, sourcePath))
    }

    const val EXTRA_SOURCE_PATH = "fmaboard_sticker_source"

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

    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** How a sticker goes into one field: the file to send and its type. */
    class Delivery(val file: File, val mime: String)

    /** The types a field takes, or an empty list. */
    fun typesOf(info: EditorInfo?): List<String> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) info?.contentMimeTypes?.toList() else null).orEmpty()

    private fun accepts(types: List<String>, wanted: String) = types.any { t ->
        t == wanted || t == "*/*" || (t.endsWith("/*") && wanted.startsWith(t.dropLast(1)))
    }

    /**
     * The best way to put [file] into a field of [pkg] that takes [types]; null if it takes no images.
     * - WhatsApp: its own sticker type, so it arrives as a sticker.
     * - Animated, anywhere else: a looping GIF (Telegram plays only the first frame of a WebP).
     * - Still, in Telegram and others that take WebP: plain WebP (Telegram turns a small WebP into a sticker;
     *   given WhatsApp's type it does not know the file and sends it as a picture).
     * - Instagram and others: a transparent PNG, which they all take (Instagram paints it on white).
     */
    fun deliveryFor(context: Context, file: File, pkg: String?, types: List<String>): Delivery? {
        Log.i(TAG, "Field of $pkg takes: ${types.joinToString()}")
        if (types.contains(MIME_STICKER) || (pkg in WHATSAPP && accepts(types, MIME_STICKER))) {
            return Delivery(file, MIME_STICKER)
        }
        // Animated: only WhatsApp plays animated WebP, everyone else gets a GIF so it keeps moving
        // (asGif is null for a still sticker)
        if (pkg !in NO_KEYBOARD_GIFS && accepts(types, "image/gif")) asGif(file)?.let { return Delivery(it, "image/gif") }
        if (pkg !in PNG_ONLY && accepts(types, "image/webp")) return Delivery(file, "image/webp")
        if (accepts(types, "image/png")) return asPng(context, file)?.let { Delivery(it, "image/png") }
        return null
    }

    // Apps that turn away GIFs from keyboards (only their own GIF search may send them)
    private val NO_KEYBOARD_GIFS = setOf("com.instagram.android", "com.instagram.barcelona")

    // Apps that say they take any image but do not show WebP properly
    private val PNG_ONLY = setOf("com.instagram.android", "com.instagram.barcelona", "com.facebook.orca")

    /** A GIF copy of an animated sticker; null if it is a still one. */
    private fun asGif(file: File): File? = try {
        val bytes = file.readBytes()
        if (!StickerGif.isAnimated(bytes)) null else StickerGif.fromAnimatedWebp(bytes)?.let { gif ->
            File(file.parentFile, file.nameWithoutExtension + ".gif").also { it.writeBytes(gif) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not make a GIF of the sticker", e)
        null
    }

    /** A PNG copy (the first frame of an animated sticker), keeping the transparency. */
    private fun asPng(context: Context, file: File): File? = try {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        File(file.parentFile, file.nameWithoutExtension + ".png").also { out ->
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not make a PNG of the sticker", e)
        null
    }

    /** Sends [file] into the field that is open now; false if that field takes no images. */
    suspend fun send(manager: KeyboardManagerForAction, file: File, ignoreConnectionOverride: Boolean = false): Boolean {
        val ime = manager.getLatinIMEForDebug()
        val info = if (ignoreConnectionOverride) ime.getBaseInputEditorInfo() else ime.currentInputEditorInfo
        val started = System.currentTimeMillis()
        val d = withContext(Dispatchers.Default) { deliveryFor(ime, file, info?.packageName, typesOf(info)) } ?: return false
        Log.i(TAG, "Sending as ${d.mime} (${d.file.length() / 1024} KB, ready in ${System.currentTimeMillis() - started} ms)")
        manager.typeUri(uriFor(ime, d.file), listOf(d.mime), ignoreConnectionOverride)
        return true
    }

    /** Called when a field opens: sends the waiting sticker if this is the app it is for. */
    @JvmStatic
    fun onStartField(ime: LatinIME, info: EditorInfo?) {
        val p = pending ?: return
        if (System.currentTimeMillis() - p.time > PENDING_MS || !p.file.exists()) { pending = null; return }
        if (p.pkg != null && p.pkg != info?.packageName) return
        // A search box or similar: keep it for the chat's own field
        if (typesOf(info).none { it.startsWith("image/") || it == "*/*" }) {
            Log.i(TAG, "Field of ${info?.packageName} takes no images, waiting for the chat")
            return
        }
        pending = null
        ime.lifecycleScope.launch {
            delay(200)
            send(ime.uixManager.keyboardManagerForAction, p.file)
        }
    }
}

