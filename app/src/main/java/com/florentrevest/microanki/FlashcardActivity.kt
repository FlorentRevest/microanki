package com.florentrevest.microanki

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen card shown on top of whichever app just came to the foreground.
 *
 * Flow: load the next due card -> show the question -> reveal the answer ->
 * grade it (which reschedules it in AnkiDroid) -> dismiss and let the user
 * back into their app.
 */
class FlashcardActivity : ComponentActivity() {

    private val anki by lazy { AnkiDroidHelper(this) }
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = appColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlashcardScreen()
                }
            }
        }
    }

    @Composable
    private fun FlashcardScreen() {
        var state by remember { mutableStateOf<CardUiState>(CardUiState.Loading) }
        var answerRevealed by remember { mutableStateOf(false) }
        var shownAt by remember { mutableStateOf(0L) }

        LaunchedEffect(Unit) {
            state = loadState()
            shownAt = System.currentTimeMillis()
        }

        // Force engagement: while a card is on screen, swallow the back gesture
        // unless the user disabled that in settings.
        val blockBack = prefs.forceAnswer && state is CardUiState.Card && !answerRevealed
        BackHandler(enabled = blockBack) { /* intentionally ignored */ }

        when (val s = state) {
            CardUiState.Loading -> Centered { CircularProgressIndicator() }

            is CardUiState.Message -> Centered {
                MessageContent(title = s.title, body = s.body, onDismiss = ::finish)
            }

            is CardUiState.Card -> CardContent(
                card = s.card,
                answerRevealed = answerRevealed,
                onReveal = { answerRevealed = true },
                onGrade = { ease ->
                    val timeTaken = System.currentTimeMillis() - shownAt
                    gradeAndFinish(s.card, ease, timeTaken)
                },
            )
        }
    }

    private suspend fun loadState(): CardUiState = withContext(Dispatchers.IO) {
        when {
            !anki.isApiAvailable() -> CardUiState.Message(
                "AnkiDroid not found",
                "Install AnkiDroid and open MicroAnki to finish setup.",
            )
            !anki.hasPermission() -> CardUiState.Message(
                "Permission needed",
                "Open MicroAnki and grant access to your AnkiDroid collection.",
            )
            !prefs.hasDeck -> CardUiState.Message(
                "No deck chosen",
                "Open MicroAnki and pick a deck to practise.",
            )
            else -> when (val card = anki.getNextCard(prefs.deckId)) {
                null -> CardUiState.Message(
                    "All caught up 🎉",
                    "No cards due in \"${prefs.deckName}\" right now.",
                )
                else -> CardUiState.Card(card)
            }
        }
    }

    private fun gradeAndFinish(card: ReviewCard, ease: Int, timeTakenMs: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { anki.answerCard(card.noteId, card.cardOrd, ease, timeTakenMs) }
            finish()
        }
    }

    @Composable
    private fun CardContent(
        card: ReviewCard,
        answerRevealed: Boolean,
        onReveal: () -> Unit,
        onGrade: (Int) -> Unit,
    ) {
        val dark = isDark()
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            CardWebView(
                html = buildCardHtml(if (answerRevealed) card.answer else card.question, dark),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(12.dp))
            if (!answerRevealed) {
                Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
                    Text("Show answer")
                }
            } else {
                GradeButtons(card = card, onGrade = onGrade)
            }
        }
    }

    @Composable
    private fun GradeButtons(card: ReviewCard, onGrade: (Int) -> Unit) {
        val buttons = easeButtonsFor(card)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buttons.forEach { btn ->
                Button(
                    onClick = { onGrade(btn.ease) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp, vertical = 10.dp,
                    ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(btn.label, style = MaterialTheme.typography.labelLarge)
                        btn.interval?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageContent(title: String, body: String, onDismiss: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onDismiss) { Text("Continue") }
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

private sealed interface CardUiState {
    data object Loading : CardUiState
    data class Card(val card: ReviewCard) : CardUiState
    data class Message(val title: String, val body: String) : CardUiState
}

private data class EaseButton(val ease: Int, val label: String, val interval: String?)

private fun easeButtonsFor(card: ReviewCard): List<EaseButton> {
    val labels = when (card.buttonCount) {
        2 -> listOf("Again", "Good")
        3 -> listOf("Again", "Good", "Easy")
        else -> listOf("Again", "Hard", "Good", "Easy")
    }
    return labels.mapIndexed { i, label ->
        EaseButton(ease = i + 1, label = label, interval = card.nextReviewTimes.getOrNull(i))
    }
}

@Composable
private fun CardWebView(html: String, modifier: Modifier = Modifier) {
    // WebView scrolls its own content natively within the bounded height it
    // gets from the parent Column's weight.
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = true
                settings.javaScriptEnabled = false
                // Keep everything inside the card; no navigating away.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, url: String?,
                    ): Boolean = true
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
    )
}

private fun buildCardHtml(content: String, dark: Boolean): String {
    val fg = if (dark) "#ECEFF1" else "#1A1A1A"
    val bg = "transparent"
    val accent = if (dark) "#90CAF9" else "#1565C0"
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body {
                margin: 0; padding: 0; background: $bg; color: $fg;
                font-family: -apple-system, Roboto, sans-serif;
                font-size: 22px; line-height: 1.5;
                -webkit-text-size-adjust: 100%;
            }
            body {
                display: flex; flex-direction: column; justify-content: center;
                min-height: 100%; text-align: center;
                padding: 16px; box-sizing: border-box; word-wrap: break-word;
            }
            hr { border: none; border-top: 1px solid #9993; margin: 20px 0; }
            img { max-width: 100%; height: auto; }
            b, strong { color: $accent; }
            .cloze { color: $accent; font-weight: bold; }
        </style>
        </head>
        <body>$content</body>
        </html>
    """.trimIndent()
}
