package org.futo.inputmethod.latin.fmaboard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
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

            val midX = screen.centerX()
            val text = lines
                .sortedWith(compareBy({ it.first.top }, { it.first.left }))
                .map { (bounds, t) ->
                    val side = when {
                        bounds.left > midX - screen.width() / 10 -> "Yo"
                        bounds.right < midX + screen.width() / 10 -> "Otro"
                        else -> null
                    }
                    if (side != null) "$side: $t" else t
                }
                .distinct()
                .joinToString("\n")
            return text.takeLast(MAX_CHARS).takeIf { it.isNotBlank() }
        }

        private fun collect(node: AccessibilityNodeInfo, out: MutableList<Pair<Rect, String>>) {
            if (!node.isVisibleToUser) return
            // The field being typed in is the user's draft, read separately
            if (node.isEditable) return
            val text = node.text?.toString()?.trim()
            // Skip timestamps ("14:02") and tiny UI labels
            if (text != null && text.length > 1 && !TIME.matches(text)) {
                out.add(Rect().also { node.getBoundsInScreen(it) } to text)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collect(it, out) }
            }
        }
    }
}
