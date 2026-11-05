package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.settings.Ios
import org.futo.inputmethod.latin.uix.settings.IosGroup
import org.futo.inputmethod.latin.uix.settings.IosIconTile
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.updates.FMABOARD_RELEASES_URL
import org.futo.inputmethod.updates.openURI
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells people in the settings when a newer FMAboard is on GitHub, with the link to get it.
 * Looks at most every few hours, only while the settings are open; nothing about the person
 * is sent (it is a plain read of the public list of releases).
 */
object FmaUpdates {
    private const val TAG = "FMAboardUpdates"
    private const val API = "https://api.github.com/repos/GeckCore/FMAboard/releases?per_page=10"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private const val PREF_LAST_CHECK = "fmaboard_update_last_check"
    private const val PREF_LATEST = "fmaboard_update_latest"
    private const val PREF_LATEST_URL = "fmaboard_update_latest_url"

    class Release(val version: String, val url: String)

    /** "0.1.10" -> [0, 1, 10]; null for tags that are not app versions (models-v1). */
    private fun parts(v: String): List<Int>? =
        v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    fun isNewer(candidate: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest release we know of, checking GitHub if it has been a while. Blocking. */
    fun latest(context: Context): Release? {
        val prefs = PreferenceUtils.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0) > CHECK_EVERY_MS) {
            fetch()?.let { r ->
                prefs.edit {
                    putLong(PREF_LAST_CHECK, now)
                    putString(PREF_LATEST, r.version)
                    putString(PREF_LATEST_URL, r.url)
                }
            }
        }
        val version = prefs.getString(PREF_LATEST, null) ?: return null
        Log.i(TAG, "latest known: $version, installed: ${BuildConfig.VERSION_NAME}")
        return Release(version, prefs.getString(PREF_LATEST_URL, null) ?: FMABOARD_RELEASES_URL)
    }

    private fun fetch(): Release? {
        val connection = URL(API).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FMAboard")
            if (connection.responseCode != 200) { Log.w(TAG, "GitHub ${connection.responseCode}"); return null }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            var best: Release? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val version = r.optString("tag_name").removePrefix("v")
                if (parts(version) == null) continue
                if (best == null || isNewer(version, best.version)) best = Release(version, r.optString("html_url", FMABOARD_RELEASES_URL))
            }
            Log.i(TAG, "latest on GitHub: ${best?.version}")
            best
        } catch (e: Exception) {
            Log.w(TAG, "could not check for updates: ${e.javaClass.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }
}

/** At the top of the settings, only when a newer version is out. */
@Composable
fun FmaUpdateBanner() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<FmaUpdates.Release?>(null) }
    LaunchedEffect(Unit) {
        release = withContext(Dispatchers.IO) { FmaUpdates.latest(context) }
            ?.takeIf { FmaUpdates.isNewer(it.version) }
    }
    val r = release ?: return
    IosGroup {
        SettingItem(
            title = "Nueva versión: ${r.version}",
            subtitle = "Tienes la ${BuildConfig.VERSION_NAME.substringBefore('-')}. Toca para descargarla e instálala encima: no se pierde nada.",
            icon = { IosIconTile(painterResource(R.drawable.fmaboard_download), Ios.Green) },
            onClick = { context.openURI(r.url, newTask = true) }
        ) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}
