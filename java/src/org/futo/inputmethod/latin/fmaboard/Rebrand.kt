package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import org.futo.inputmethod.latin.uix.PreferenceUtils
import java.io.File

/**
 * The project was called AImHuman before FMAboard. Installs from then keep their settings,
 * OpenRouter key, typing log and personal autocorrect: this moves them to the new names once.
 */
object Rebrand {
    private const val OLD = "aimhuman"
    private const val NEW = "fmaboard"
    private const val PREF_DONE = "fmaboard_rebranded"
    /** Read by [TypingLogStore]: copy every log file to the new shared folder, not only new ones. */
    const val PREF_REMIRROR = "fmaboard_remirror"
    const val OLD_MIRROR_ROOT = "AImHuman"

    @Synchronized
    @JvmStatic
    fun migrate(context: Context) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (!userManager.isUserUnlocked) return
        val prefs = PreferenceUtils.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_DONE, false)) return
        try {
            prefs.edit {
                for ((key, value) in prefs.all) {
                    if (!key.startsWith("${OLD}_")) continue
                    val newKey = NEW + key.removePrefix(OLD)
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is Boolean -> putBoolean(newKey, value)
                        is String -> putString(newKey, value)
                        is Int -> putInt(newKey, value)
                        is Long -> putLong(newKey, value)
                        is Float -> putFloat(newKey, value)
                        is Set<*> -> putStringSet(newKey, value as Set<String>)
                    }
                    remove(key)
                }
            }
            val files = context.filesDir
            val oldLog = File(files, OLD)
            if (oldLog.isDirectory && !File(files, NEW).exists()) oldLog.renameTo(File(files, NEW))
            val oldRules = File(files, "${OLD}_autocorrect.json")
            if (oldRules.exists() && !File(files, "${NEW}_autocorrect.json").exists()) {
                oldRules.renameTo(File(files, "${NEW}_autocorrect.json"))
            }
            prefs.edit {
                putBoolean(PREF_REMIRROR, oldLog.exists() || File(files, NEW).exists())
                putBoolean(PREF_DONE, true)
            }
        } catch (e: Exception) {
            Log.e("FMAboardRebrand", "Migration failed", e)
        }
    }
}
