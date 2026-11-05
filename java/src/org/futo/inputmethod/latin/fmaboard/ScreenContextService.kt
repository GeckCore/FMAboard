package org.futo.inputmethod.latin.fmaboard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the conversation visible on screen, only when "Responder con mi estilo" asks for it.
 *
 * The service ignores every accessibility event: it does nothing in the background and
 * stores nothing. Text read here is never written to the typing log.
 */
class ScreenContextService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: ScreenContextService? = null

        private const val MAX_CHARS = 3000
        private val TIME = Regex("^\\d{1,2}:\\d{2}( ?[ap]\\.? ?m\\.?)?$", RegexOption.IGNORE_CASE)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, ScreenContextService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            } && instance != null
        }

        /**
         * The text of the app window behind the keyboard, top to bottom. In chat apps,
         * bubbles on the right are the user's ("Yo"), on the left the other person's.
         * Returns null if the service is off or nothing could be read.
         */
        fun readConversation(appPackage: String?): String? {
            val service = instance ?: return null
            val root = service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { appPackage == null || it.packageName == appPackage }
                ?: return null

            val screen = Rect().also { root.getBoundsInScreen(it) }
            val lines = ArrayList<Pair<Rect, String>>()
            collect(root, lines)
            if (lines.isEmpty()) return null

                val text = lines
                .sortedWith(compareBy({ it.first.top }, { it.first.left }))
                .map { (bounds, t) ->
                    // Which side the text leans to: right is the user's, left the other person's.
                    // Text running from the left edge across (a long reply) is theirs too.
                    val w = screen.width()
                    val leftGap = bounds.left - screen.left
                    val rightGap = screen.right - bounds.right
                    val side = when {
                        leftGap - rightGap > w / 20 -> "Yo"
                        rightGap - leftGap > w / 20 -> "Otro"
                        leftGap < w / 10 -> "Otro"
                        else -> null
                    }
                    if (side != null) "$side: $t" else t
                }
                .distinct()
                .joinToString("\n")
            return text.takeLast(MAX_CHARS).takeIf { it.isNotBlank() }
        }

        /**
         * Where the letters really are. A text box can span the whole width while its text sits on
         * one side (Gemini, many Compose apps), so ask for the position of the characters when the
         * app offers it and fall back to the box.
         */
        private fun textBounds(node: AccessibilityNodeInfo, text: String): Rect {
            val box = Rect().also { node.getBoundsInScreen(it) }
            val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            if (!node.availableExtraData.contains(key)) return box
            return try {
                val length = minOf(text.length, 200)
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
                }
                if (!node.refreshWithExtraData(key, args)) return box
                @Suppress("DEPRECATION")
                val rects = node.extras.getParcelableArray(key)?.filterIsInstance<RectF>()?.filter { !it.isEmpty }
                if (rects.isNullOrEmpty()) return box
                val letters = Rect(rects.minOf { it.left }.toInt(), rects.minOf { it.top }.toInt(),
                    rects.maxOf { it.right }.toInt(), rects.maxOf { it.bottom }.toInt())
                if (letters.width() > 0) letters else box
            } catch (e: Exception) {
                box
            }
        }

        private fun collect(node: AccessibilityNodeInfo, out: MutableList<Pair<Rect, String>>) {
            if (!node.isVisibleToUser) return
            // The field being typed in is the user's draft, read separately
            if (node.isEditable) return
            val text = node.text?.toString()?.trim()
            // Skip timestamps ("14:02") and tiny UI labels
            if (text != null && text.length > 1 && !TIME.matches(text)) {
                // One message per line: a message with several paragraphs must not lose its
                // "Yo:"/"Otro:" mark after the first line break
                out.add(textBounds(node, text) to text.replace(Regex("\\s*\\n\\s*"), " "))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collect(it, out) }
            }
        }
    }
}
