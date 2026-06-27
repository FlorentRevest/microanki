package com.florentrevest.microanki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.json.JSONArray

/** A deck the user can choose to draw cards from. */
data class DeckInfo(val id: Long, val name: String)

/**
 * One card pulled from AnkiDroid's scheduler, ready to be reviewed.
 *
 * [question] and [answer] are the fully rendered HTML produced by AnkiDroid
 * (the same markup the AnkiDroid reviewer shows). [nextReviewTimes] holds the
 * interval label for each answer button ("<1m", "1d", ...).
 */
data class ReviewCard(
    val noteId: Long,
    val cardOrd: Int,
    val question: String,
    val answer: String,
    val buttonCount: Int,
    val nextReviewTimes: List<String>,
)

/**
 * Thin convenience layer over the AnkiDroid database ContentProvider.
 *
 * Everything here does blocking I/O against another app's provider, so call it
 * off the main thread.
 */
class AnkiDroidHelper(context: Context) {

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    /** True when an AnkiDroid build exposing the API is installed. */
    fun isApiAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(context) != null

    /** True when the user has granted us read/write access to the collection. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** All decks in the collection, sorted by name. */
    fun getDecks(): List<DeckInfo> {
        val decks = mutableListOf<DeckInfo>()
        try {
            resolver.query(
                FlashCardsContract.Deck.CONTENT_ALL_URI, null, null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(FlashCardsContract.Deck.DECK_ID)
                val nameIdx = cursor.getColumnIndex(FlashCardsContract.Deck.DECK_NAME)
                if (idIdx < 0 || nameIdx < 0) return emptyList()
                while (cursor.moveToNext()) {
                    decks += DeckInfo(cursor.getLong(idIdx), cursor.getString(nameIdx))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDecks failed", e)
        }
        return decks.sortedBy { it.name.lowercase() }
    }

    /**
     * Fetch the next card due for review in [deckId], or null when nothing is
     * due (or AnkiDroid is unreachable).
     */
    fun getNextCard(deckId: Long): ReviewCard? {
        try {
            val selection = "limit=?, deckID=?"
            val args = arrayOf("1", deckId.toString())
            resolver.query(
                FlashCardsContract.ReviewInfo.CONTENT_URI, null, selection, args, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null

                val noteId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NOTE_ID)
                )
                val cardOrd = cursor.getInt(
                    cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.CARD_ORD)
                )
                val buttonCount = cursor.indexOrNull(FlashCardsContract.ReviewInfo.BUTTON_COUNT)
                    ?.let { cursor.getInt(it) } ?: 4
                val nextTimes = cursor.indexOrNull(FlashCardsContract.ReviewInfo.NEXT_REVIEW_TIMES)
                    ?.let { parseNextReviewTimes(cursor.getString(it)) } ?: emptyList()

                val (question, answer) = loadQuestionAndAnswer(noteId, cardOrd) ?: return null
                return ReviewCard(noteId, cardOrd, question, answer, buttonCount, nextTimes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getNextCard failed", e)
        }
        return null
    }

    private fun loadQuestionAndAnswer(noteId: Long, cardOrd: Int): Pair<String, String>? {
        val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val cardUri = Uri.withAppendedPath(cardsUri, cardOrd.toString())
        resolver.query(cardUri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val question = cursor.getString(
                cursor.getColumnIndexOrThrow(FlashCardsContract.Card.QUESTION)
            )
            val answer = cursor.getString(
                cursor.getColumnIndexOrThrow(FlashCardsContract.Card.ANSWER)
            )
            return question to answer
        }
        return null
    }

    /**
     * Report an answer back to AnkiDroid so the card is rescheduled.
     *
     * @param ease 1-based answer button (1 = Again ... up to the card's button count).
     * @param timeTakenMs how long the user spent on the card.
     */
    fun answerCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long): Boolean {
        return try {
            val values = ContentValues().apply {
                put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
                put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
                put(FlashCardsContract.ReviewInfo.EASE, ease)
                put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTakenMs)
            }
            resolver.update(FlashCardsContract.ReviewInfo.CONTENT_URI, values, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "answerCard failed", e)
            false
        }
    }

    private fun parseNextReviewTimes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            raw.trim('[', ']').split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        }
    }

    private fun android.database.Cursor.indexOrNull(column: String): Int? =
        getColumnIndex(column).takeIf { it >= 0 }

    companion object {
        private const val TAG = "AnkiDroidHelper"
        const val READ_WRITE_PERMISSION = FlashCardsContract.READ_WRITE_PERMISSION
    }
}
