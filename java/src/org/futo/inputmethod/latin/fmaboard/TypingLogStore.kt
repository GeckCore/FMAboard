package org.futo.inputmethod.latin.fmaboard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.futo.inputmethod.latin.uix.PreferenceUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File storage for the typing log.
 *
 * Two append-only JSONL streams per local day, in app-private storage:
 *   files/fmaboard/2026-11-02.keystrokes.jsonl   every raw event
 *   files/fmaboard/2026-11-02.messages.jsonl     one clean record per message
 *
 * Every few minutes (and when a message ends) changed files are copied automatically to
 * the shared folder Documents/FMAboard/YYYY-MM/ so the data survives uninstalling the app
 * and is reachable from a PC or file manager.
 *
 * All disk work happens on one low-priority background thread.
 */
object TypingLogStore {
    private const val TAG = "FMAboardTypingLog"

    enum class Stream(val suffix: String) {
        KEYSTROKES(".keystrokes.jsonl"),
        MESSAGES(".messages.jsonl"),
    }

    private const val DIR_NAME = "fmaboard"
    private const val MIRROR_ROOT = "FMAboard"
    private const val FLUSH_EVERY_EVENTS = 64
    private const val FLUSH_INTERVAL_SECONDS = 5L
    private const val MIRROR_MIN_INTERVAL_MS = 2 * 60 * 1000L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "FMAboardTypingLog").apply { priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var appContext: Context? = null

    // Everything below is only touched on the executor thread.
    private val pending = ArrayList<Pair<Stream, String>>()
    private val writers = HashMap<Stream, BufferedWriter>()
    private var writersDay: String? = null
    private val dirtyFiles = HashSet<String>()
    private var lastMirrorTime = 0L
    private val mirrorUris = HashMap<String, Uri>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        executor.execute {
            // Renamed from AImHuman: the new shared folder gets the whole log, not only new days
            val prefs = PreferenceUtils.getDefaultSharedPreferences(context)
            if (prefs.getBoolean(Rebrand.PREF_REMIRROR, false)) {
                logFiles(context).forEach { dirtyFiles.add(it.name) }
                prefs.edit().putBoolean(Rebrand.PREF_REMIRROR, false).apply()
            }
        }
        executor.scheduleWithFixedDelay({ flushNow(); mirrorIfDue(force = false) },
            FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    fun append(stream: Stream, line: String) {
        executor.execute {
            pending.add(stream to line)
            if (pending.size >= FLUSH_EVERY_EVENTS) flushNow()
        }
    }

    /** Called when a message ends: write now and refresh the shared copy soon. */
    fun onMessageEnded() {
        executor.execute {
            flushNow()
            mirrorIfDue(force = false)
        }
    }

    fun logDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun logFiles(context: Context): List<File> =
        logDir(context).listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()

    /** Blocks until queued data is on disk and mirrored. Call off the main thread. */
    fun flushBlocking() {
        try {
            executor.submit { flushNow(); mirrorIfDue(force = true) }.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "flushBlocking failed", e)
        }
    }

    fun exportZip(context: Context, out: OutputStream) {
        flushBlocking()
        ZipOutputStream(out).use { zip ->
            logFiles(context).forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun deleteAll(context: Context) {
        executor.submit {
            pending.clear()
            closeWriters()
            dirtyFiles.clear()
            logFiles(context).forEach { it.delete() }
            deleteMirror(context)
        }.get(30, TimeUnit.SECONDS)
    }

    // --- Executor thread ----------------------------------------------------------------

    private fun flushNow() {
        if (pending.isEmpty()) return
        val context = appContext ?: return
        try {
            val day = dayFormat().format(Date())
            if (writersDay != day) {
                closeWriters()
                writersDay = day
            }
            for ((stream, line) in pending) {
                val w = writers.getOrPut(stream) {
                    val dir = logDir(context).apply { mkdirs() }
                    BufferedWriter(OutputStreamWriter(
                        FileOutputStream(File(dir, day + stream.suffix), true), Charsets.UTF_8))
                }
                w.write(line)
                w.write('\n'.code)
                dirtyFiles.add(day + stream.suffix)
            }
            writers.values.forEach { it.flush() }
            pending.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write typing log", e)
            closeWriters()
        }
    }

    private fun closeWriters() {
        writers.values.forEach { try { it.close() } catch (_: Exception) {} }
        writers.clear()
        writersDay = null
    }

    private fun mirrorIfDue(force: Boolean) {
        if (dirtyFiles.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val now = System.currentTimeMillis()
        if (!force && now - lastMirrorTime < MIRROR_MIN_INTERVAL_MS) return
        val context = appContext ?: return
        lastMirrorTime = now

        val iterator = dirtyFiles.iterator()
        while (iterator.hasNext()) {
            val name = iterator.next()
            val source = File(logDir(context), name)
            if (!source.exists()) { iterator.remove(); continue }
            try {
                val uri = mirrorUri(context, name) ?: continue
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                iterator.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mirror $name", e)
                mirrorUris.remove(name)
            }
        }
    }

    // Finds the shared copy this install created, or creates it.
    private fun mirrorUri(context: Context, name: String): Uri? {
        mirrorUris[name]?.let { return it }
        val relativePath = Environment.DIRECTORY_DOCUMENTS + "/" + MIRROR_ROOT + "/" + name.substring(0, 7) + "/"
        val collection = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, name),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                mirrorUris[name] = uri
                return uri
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        return context.contentResolver.insert(collection, values)?.also { mirrorUris[name] = it }
    }

    private fun deleteMirror(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            for (root in listOf(MIRROR_ROOT, Rebrand.OLD_MIRROR_ROOT)) context.contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(Environment.DIRECTORY_DOCUMENTS + "/" + root + "/%")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete mirror", e)
        }
        mirrorUris.clear()
    }

    private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
}
