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
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Our own flashcard and the system UI are "transparent": they must not
        // count as leaving the underlying app, otherwise returning to it would
        // immediately trigger another card (an infinite loop when cooldown = 0).
        if (pkg == packageName || pkg in TRANSPARENT_PACKAGES) return

        // Only window changes belonging to an actual activity matter. This
        // filters out toasts, popups and IME windows that share a package.
        if (!isActivityWindow(event)) return

        if (pkg == lastPackage) return // internal navigation within the same app
        lastPackage = pkg

        if (pkg !in prefs.triggerPackages) return
        if (!cooldownElapsed()) return

        showFlashcard()
    }

    private fun cooldownElapsed(): Boolean {
        val cooldownMs = prefs.cooldownMinutes.toLong() * 60_000L
        if (cooldownMs <= 0L) return true
        return System.currentTimeMillis() - prefs.lastShownAt >= cooldownMs
    }

    private fun showFlashcard() {
        prefs.lastShownAt = System.currentTimeMillis()
        val intent = Intent(this, FlashcardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch flashcard", e)
        }
    }

    private fun isActivityWindow(event: AccessibilityEvent): Boolean {
        // Activities report their class; most non-activity windows report a
        // widget class name. Treat an unknown/blank class as an activity to
        // avoid missing launches on OEMs that don't populate it.
        val className = event.className ?: return true
        return !TextUtils.isEmpty(className)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AppMonitorService"

        private val TRANSPARENT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
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
