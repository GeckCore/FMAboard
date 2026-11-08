package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.fmaboard.ClipboardRetention
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses isolated test files; never reads or alters the person's real clipboard. */
@RunWith(AndroidJUnit4::class)
class ClipboardCleanupTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "clipboard-cleanup-test-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        context.clipboardDir.mkdirs()
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun entry(ageHours: Long, pinned: Boolean = false, image: String? = null) = ClipboardEntry(
        timestamp = System.currentTimeMillis() - ageHours * ClipboardRetention.HOUR_MILLIS,
        pinned = pinned, text = if (image == null) "test-$ageHours-$pinned" else null,
        uri = null, mimeTypes = listOf(if (image == null) "text/plain" else "image/png"),
        backingFile = image
    )

    private fun write(file: File, entries: List<ClipboardEntry>) {
        file.writeText(Json.encodeToString(entries))
    }

    private fun read(file: File): List<ClipboardEntry> = Json.decodeFromString(file.readText())

    @Test fun expiresOldTextAndImagesInPrimaryAndRecoveryFiles() {
        val pinned = entry(100, pinned = true, image = "pinned.png")
        val recent = entry(1)
        val entries = listOf(entry(25), entry(30, image = "expired.png"), recent, pinned)
        val backup = File(root, "$ClipboardFileName.backup")
        val swap = File(root, "$ClipboardFileName.swap")
        listOf(context.clipboardFile, backup, swap).forEach { write(it, entries) }
        listOf("expired.png", "pinned.png").forEach {
            val image = File(context.clipboardDir, it).apply { writeText("test image") }
            ClipboardUtil.thumbnailFor(image).writeText("test thumbnail")
        }

        ClipboardCleanup.pruneStoredHistory(context, 24, includePrimary = true)

        listOf(context.clipboardFile, backup, swap).forEach { assertEquals(listOf(recent, pinned), read(it)) }
        assertFalse(File(context.clipboardDir, "expired.png").exists())
        assertFalse(File(context.clipboardDir, "expired.png.thumb.jpg").exists())
        assertTrue(File(context.clipboardDir, "pinned.png").exists())
        assertTrue(File(context.clipboardDir, "pinned.png.thumb.jpg").exists())
    }

    @Test fun keepsAnImageStillReferencedByAPinnedClip() {
        val pinned = entry(100, pinned = true, image = "shared.png")
        write(context.clipboardFile, listOf(pinned))
        val backup = File(root, "$ClipboardFileName.bak")
        write(backup, listOf(entry(30, image = "shared.png")))
        val image = File(context.clipboardDir, "shared.png").apply { writeText("test image") }
        ClipboardCleanup.pruneStoredHistory(context, 24, includePrimary = true)
        assertTrue(image.exists())
        assertEquals(listOf(pinned), read(context.clipboardFile))
        assertEquals(listOf(pinned), read(File(root, "$ClipboardFileName.backup")))
    }

    @Test fun damagedRecoveryCopyDoesNotPreventCleaningHealthyPrimary() {
        val recent = entry(1)
        write(context.clipboardFile, listOf(entry(25), recent))
        File(root, "$ClipboardFileName.backup").writeText("damaged test data")
        ClipboardCleanup.pruneStoredHistory(context, 24, includePrimary = true)
        assertEquals(listOf(recent), read(context.clipboardFile))
    }

    @Test fun oldPinnedRecoveryCopyCannotResurrectAnUnpinnedExpiredClip() {
        val unpinned = entry(25, image = "unpinned.png")
        write(context.clipboardFile, listOf(unpinned))
        val backup = File(root, "$ClipboardFileName.backup")
        write(backup, listOf(unpinned.copy(pinned = true)))
        ClipboardCleanup.pruneStoredHistory(context, 24, includePrimary = true)
        assertTrue(read(context.clipboardFile).isEmpty())
        assertTrue(read(backup).isEmpty())
    }
}
