package org.futo.inputmethod.latin.uix.actions.clipboard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.fmaboard.ClipboardRetention
import org.futo.inputmethod.latin.uix.forceUnlockDatastore
import org.futo.inputmethod.latin.uix.getUnlockedSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import java.io.File

/** FMAboard: also expire history when the keyboard process is not running. */
internal object ClipboardCleanup {
    private const val JOB_ID = 0x464d43
    // AtomicFile instances have no shared lock of their own.
    val fileLock = Any()
    private val migrated = SettingsKey(booleanPreferencesKey("fmaboard_clipboard_retention_24h"), false)

    suspend fun retentionHours(context: Context): Int {
        // Read the unlocked store directly: a newly started background process may not
        // have populated the settings cache yet.
        checkNotNull(forceUnlockDatastore(context)) { "Clipboard settings are locked" }
        if (context.getUnlockedSetting(migrated) == false) {
            context.setSetting(ClipboardHistoryTimeToKeep, ClipboardRetention.DEFAULT_HOURS)
            context.setSetting(migrated, true)
            return ClipboardRetention.DEFAULT_HOURS
        }
        return context.getUnlockedSetting(ClipboardHistoryTimeToKeep) ?: ClipboardRetention.DEFAULT_HOURS
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, ClipboardCleanupJob::class.java))
            .setPeriodic(ClipboardRetention.HOUR_MILLIS)
            .setPersisted(true)
            .build())
    }

    /** Scrub recovery copies too, so an expired clip cannot come back after recovery. */
    fun pruneStoredHistory(
        context: Context,
        hours: Int,
        includePrimary: Boolean,
        liveEntries: List<ClipboardEntry>? = null
    ) = synchronized(fileLock) {
        val primary = context.clipboardFile
        val legacyBackup = File(context.filesDir, "$ClipboardFileName.bak")
        val backup = File(context.filesDir, "$ClipboardFileName.backup")
        if (legacyBackup.exists()) legacyBackup.renameTo(backup)
        val files = buildList {
            if (includePrimary) add(primary)
            add(backup)
            add(File(context.filesDir, "$ClipboardFileName.swap"))
        }
        val now = System.currentTimeMillis()
        val removedFiles = mutableSetOf<String>()
        var unreadableCopy = false
        fun read(file: File): List<ClipboardEntry>? = try {
            Json.decodeFromString<List<ClipboardEntry>>(AtomicFile(file).readFully().decodeToString())
        } catch (e: Exception) {
            // Keep damaged recovery data for the existing recovery flow. It must not
            // prevent saving a healthy primary history.
            unreadableCopy = true
            if (includePrimary && file == primary) throw e
            Log.w("ClipboardCleanup", "Could not read clipboard recovery copy", e)
            null
        }
        val current = liveEntries ?: if (primary.exists()) read(primary).orEmpty() else emptyList()
        val currentByContent = current.associateBy { it.text to it.backingFile }
        for (file in files) {
            val atomic = AtomicFile(file)
            if (!file.exists()) continue
            val entries = read(file) ?: continue
            // Unpinning must also unpin the recovery copy; otherwise it could retain
            // the old pinned version forever after the live clip expires.
            val remaining = entries.map { entry ->
                val live = currentByContent[entry.text to entry.backingFile]
                if (file != primary && live != null)
                    entry.copy(pinned = live.pinned, timestamp = live.timestamp)
                else entry
            }.filterNot {
                ClipboardRetention.isExpired(it.timestamp, it.pinned, now, hours)
            }
            if (remaining == entries) continue
            val stream = atomic.startWrite()
            try {
                stream.write(Json.encodeToString(remaining).toByteArray())
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
            removedFiles.addAll((entries - remaining.toSet()).mapNotNull { it.backingFile })
        }
        // A shared image can still belong to a pinned clip or a recovery copy.
        val referenced = if (removedFiles.isEmpty()) emptySet() else
            (files + primary).distinct().filter { it.exists() }.flatMap { read(it).orEmpty() }
                .mapNotNull { it.backingFile }.toSet()
        for (name in if (unreadableCopy) emptySet() else removedFiles - referenced) {
            val image = File(context.clipboardDir, name)
            image.delete()
            ClipboardUtil.thumbnailFor(image).delete()
        }
    }
}

class ClipboardCleanupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            var retry = false
            try {
                if (!applicationContext.isDirectBootUnlocked) {
                    retry = true
                } else {
                    val active = ClipboardHistoryManager.activeInstance?.get()
                    if (active != null) {
                        active.pruneOldItems()
                        active.saveClipboard()?.join()
                        retry = active.clipboardIOFailure.value
                    } else {
                        val hours = ClipboardCleanup.retentionHours(applicationContext)
                        withContext(Dispatchers.IO + NonCancellable) {
                            ClipboardCleanup.pruneStoredHistory(applicationContext, hours, includePrimary = true)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ClipboardCleanup", "Could not expire clipboard history", e)
                retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
