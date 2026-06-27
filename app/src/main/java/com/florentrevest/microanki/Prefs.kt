package com.florentrevest.microanki

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences holding all user configuration:
 * which deck to draw from, which apps act as triggers, and the rate-limit.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var deckId: Long
        get() = sp.getLong(KEY_DECK_ID, NO_DECK)
        set(value) = sp.edit().putLong(KEY_DECK_ID, value).apply()

    var deckName: String
        get() = sp.getString(KEY_DECK_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_DECK_NAME, value).apply()

    /** Packages whose opening should trigger a flashcard. */
    var triggerPackages: Set<String>
        get() = sp.getStringSet(KEY_TRIGGERS, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_TRIGGERS, value).apply()

    /** Minimum minutes between two flashcards. 0 means "every time an app is opened". */
    var cooldownMinutes: Int
        get() = sp.getInt(KEY_COOLDOWN, 0)
        set(value) = sp.edit().putInt(KEY_COOLDOWN, value).apply()

    /** Whether the back button is blocked until the card is answered. */
    var forceAnswer: Boolean
        get() = sp.getBoolean(KEY_FORCE, true)
        set(value) = sp.edit().putBoolean(KEY_FORCE, value).apply()

    var lastShownAt: Long
        get() = sp.getLong(KEY_LAST_SHOWN, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SHOWN, value).apply()

    val hasDeck: Boolean get() = deckId != NO_DECK

    companion object {
        const val NO_DECK = -1L

        private const val NAME = "microanki_prefs"
        private const val KEY_DECK_ID = "deck_id"
        private const val KEY_DECK_NAME = "deck_name"
        private const val KEY_TRIGGERS = "trigger_packages"
        private const val KEY_COOLDOWN = "cooldown_minutes"
        private const val KEY_FORCE = "force_answer"
        private const val KEY_LAST_SHOWN = "last_shown_at"
    }
}
