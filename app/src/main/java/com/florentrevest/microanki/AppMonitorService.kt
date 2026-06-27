package com.florentrevest.microanki

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Watches window-state changes so we can notice the moment one of the user's
 * chosen apps (Instagram, YouTube, ...) comes to the foreground and pop a
 * flashcard on top of it.
 *
 * We only ever read the package name of the foreground window — never any
 * screen content (see accessibility_service_config.xml).
 */
class AppMonitorService : AccessibilityService() {

    private lateinit var prefs: Prefs

    /**
     * The last "real" foreground app we settled on. Used to detect a genuine
     * switch *into* a trigger app rather than internal navigation, and to avoid
     * re-triggering when the user returns from the flashcard to the same app.
     */
    private var currentApp: String? = null

    /** When we last left each app, so we can tell a real return from a detour. */
    private val leftAppAt = HashMap<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Our own flashcard, the system UI and the keyboard are "transparent":
        // they must not count as leaving the app underneath, otherwise coming
        // back to it would look like a fresh open and fire another card.
        if (pkg == packageName || pkg in TRANSPARENT_PACKAGES) return
        if (pkg == currentInputMethodPackage()) return

        // Only window changes belonging to an actual activity matter. This
        // filters out toasts, popups and dialogs that ride on another package.
        if (!isActivityWindow(event)) return

        val previous = currentApp
        if (pkg == previous) return // internal navigation within the same app

        val now = System.currentTimeMillis()
        previous?.let { leftAppAt[it] = now }
        currentApp = pkg

        // First app we see after the service starts: adopt it silently rather
        // than ambushing the user in the middle of whatever they were doing.
        if (previous == null) return

        if (pkg !in prefs.triggerPackages) return

        // A genuine "I'm opening this app now" moment, not a quick hop out and
        // back (following a link, checking a notification, copying something).
        val awayMs = now - (leftAppAt[pkg] ?: 0L)
        if (awayMs < REENTRY_GRACE_MS) return

        if (!cooldownElapsed()) return

        showFlashcard()
    }

    /** Package of the keyboard currently in use, so its window can be ignored. */
    private fun currentInputMethodPackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }

    private fun cooldownElapsed(): Boolean {
        val cooldownMs = prefs.cooldownMinutes.toLong() * 60_000L
        if (cooldownMs <= 0L) return true
        return System.currentTimeMillis() - prefs.lastShownAt >= cooldownMs
    }

    private fun showFlashcard() {
        prefs.lastShownAt = System.currentTimeMillis()
        val intent = Intent(this, FlashcardActivity::class.java).apply {
            // NO_ANIMATION: the card should land on the app the moment it opens,
            // instead of sliding in after the app's own launch animation.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch flashcard", e)
        }
    }

    private fun isActivityWindow(event: AccessibilityEvent): Boolean {
        // Activities report their class; most non-activity windows report a
        // framework widget class name. Treat an unknown/blank class as an
        // activity to avoid missing launches on OEMs that don't populate it.
        val className = event.className ?: return true
        if (TextUtils.isEmpty(className)) return true
        return NON_ACTIVITY_WINDOWS.none { className.startsWith(it) }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AppMonitorService"

        /**
         * How long you must have been away from an app before opening it counts
         * as a fresh open. Stops a quick detour to another app (and back) from
         * interrupting what you were in the middle of.
         */
        private const val REENTRY_GRACE_MS = 60_000L

        private val TRANSPARENT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )

        /** Window classes that are not the app you are now using. */
        private val NON_ACTIVITY_WINDOWS = listOf(
            "android.widget.PopupWindow",
            "android.widget.Toast",
            "android.inputmethodservice.",
            "android.app.Dialog",
            "android.app.AlertDialog",
            "androidx.appcompat.app.AlertDialog",
        )

        /** Whether the accessibility service is currently enabled by the user. */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${AppMonitorService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
