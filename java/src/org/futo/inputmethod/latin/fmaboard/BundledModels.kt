package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.extension
import org.futo.inputmethod.latin.uix.getActiveLanguages
import org.futo.inputmethod.latin.uix.namePreferenceKeyFor
import org.futo.inputmethod.latin.uix.preferenceKeyFor
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.xlm.ModelPaths
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The APK carries a Spanish dictionary, prediction model and voice model (assets/fmaboard/,
 * downloaded at build time). The first time a Spanish layout is active, whatever Spanish is
 * missing is set up from them, as if it had been imported by hand. What the user already
 * imported is never replaced, and once done it is not redone (so a deleted model stays deleted).
 */
object BundledModels {
    private const val TAG = "FMAboardModels"
    private const val LANGUAGE = "es"
    private const val PREF_DONE = "fmaboard_bundled_models_es"
    private const val ASSET_DIR = "fmaboard"

    private val running = AtomicBoolean(false)

    @JvmStatic
    fun installIfNeeded(context: Context) {
        val app = context.applicationContext
        val userManager = app.getSystemService(Context.USER_SERVICE) as UserManager
        if (!userManager.isUserUnlocked) return
        val prefs = PreferenceUtils.getDefaultSharedPreferences(app)
        if (prefs.getBoolean(PREF_DONE, false)) return
        if (!running.compareAndSet(false, true)) return

        Thread({
            try {
                val spanish = getActiveLanguages(app).any { it.tag.split('_', '-')[0] == LANGUAGE }
                if (spanish) {
                    install(app)
                    prefs.edit { putBoolean(PREF_DONE, true) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not set up the bundled Spanish models", e)
            } finally {
                running.set(false)
            }
        }, TAG).start()
    }

    private fun install(context: Context) = runBlocking {
        val locale = Locale(LANGUAGE)
        var changed = false

        if (ResourceHelper.findKeyForLocaleAndKind(context, locale, FileKind.Dictionary) == null) {
            changed = importFile(context, FileKind.Dictionary, "main_es.dict", "Español") or changed
        }
        if (ResourceHelper.findKeyForLocaleAndKind(context, locale, FileKind.VoiceInput) == null) {
            changed = importFile(context, FileKind.VoiceInput, "voice-input-multilingual-74.bin", null) or changed
        }
        if (LANGUAGE !in ModelPaths.getModelOptions(context)) {
            val file = File(ModelPaths.getModelDirectory(context), "fmaboard_es_typing.gguf")
            if (copyAsset(context, "es_typing_Q6_K.gguf", file)) {
                ModelPaths.updateModelOption(context, LANGUAGE, file)
            }
        }

        if (changed) GlobalIMEMessage.tryEmit(IMEMessage.ReloadResources)
    }

    /** Same file name and settings as importing the file from "Idiomas y modelos". */
    private suspend fun importFile(context: Context, kind: FileKind, asset: String, name: String?): Boolean {
        val fileName = "${kind.name.lowercase()}_$LANGUAGE${kind.extension()}"
        if (!copyAsset(context, asset, File(context.getExternalFilesDir(null), fileName))) return false
        context.setSetting(kind.preferenceKeyFor(LANGUAGE), fileName)
        name?.let { context.setSetting(kind.namePreferenceKeyFor(LANGUAGE), it) }
        return true
    }

    private fun copyAsset(context: Context, asset: String, target: File): Boolean = try {
        val part = File(target.parentFile, target.name + ".part")
        context.assets.open("$ASSET_DIR/$asset").use { input ->
            part.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        target.delete()
        part.renameTo(target)
    } catch (e: Exception) {
        Log.e(TAG, "Could not copy $asset", e)
        false
    }
}
