package com.florentrevest.microanki

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card shown as a dialog floating on top of whichever app just came to the
 * foreground — the app stays visible behind a dimming scrim.
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
                FlashcardScreen()
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

        Dialog {
            when (val s = state) {
                CardUiState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is CardUiState.Message ->
                    MessageContent(title = s.title, body = s.body, onDismiss = ::finish)

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

    /** Dimmed backdrop (the app underneath shows through) with a centred card. */
    @Composable
    private fun Dialog(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(24.dp)) { content() }
            }
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
        val html = if (answerRevealed) card.answer else card.question
        // The box gives the card a comfortable minimum height and caps it so a
        // wordy card scrolls instead of filling the screen; the WebView inside
        // measures to its own content, so a short card is exactly as tall as it
        // needs to be and never reports itself as scrollable.
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
            contentAlignment = Alignment.Center,
        ) {
            // A WebView always measures its own scroll range slightly larger
            // than the box it is given, so it shows a scrollbar even when the
            // content plainly fits. Vocabulary cards are just text, so render
            // them with Compose and keep the WebView for cards that genuinely
            // need a browser (images, tables, ...).
            if (needsWebView(html)) {
                CardWebView(
                    html = buildCardHtml(html, dark),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CardText(html)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!answerRevealed) {
            Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
                Text("Show answer")
            }
        } else {
            GradeButtons(card = card, onGrade = onGrade)
        }
    }

    /** A plain-text card: the word (and, once revealed, its translation). */
    @Composable
    private fun CardText(html: String) {
        val sections = remember(html) { plainTextSections(html) }
        val size = remember(sections) { fontSizeFor(sections.joinToString(" ")) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(0.5f).padding(vertical = 16.dp),
                    )
                }
                Text(
                    text = section,
                    fontSize = size.sp,
                    lineHeight = (size * 1.35f).sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    @Composable
    private fun GradeButtons(card: ReviewCard, onGrade: (Int) -> Unit) {
        val buttons = easeButtonsFor(card)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            buttons.forEach { btn ->
                Button(
                    onClick = { onGrade(btn.ease) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(btn.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        btn.interval?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onDismiss) { Text("Continue") }
        }
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
    // gets from the parent.
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // WRAP_CONTENT so Compose measures it with an AT_MOST spec and
                // the view ends up exactly as tall as the card needs.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
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

/** Cards that a WebView is actually needed for; everything else is text. */
private val RICH_CONTENT =
    Regex("<\\s*(img|table|video|audio|iframe|svg|object|embed)\\b", RegexOption.IGNORE_CASE)

private fun needsWebView(html: String): Boolean = RICH_CONTENT.containsMatchIn(html)

private val HR = Regex("<\\s*hr[^>]*>", RegexOption.IGNORE_CASE)
private val LINE_BREAK =
    Regex("<\\s*/?\\s*(br|div|p|li|tr)[^>]*>", RegexOption.IGNORE_CASE)
private val SOUND_TAG = Regex("\\[sound:[^\\]]*\\]", RegexOption.IGNORE_CASE)
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);", RegexOption.IGNORE_CASE)

private val NAMED_ENTITIES = listOf(
    "&nbsp;" to "\u00A0",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&amp;" to "&", // last: its replacement must not be re-scanned
)

/**
 * Anki puts the question, a horizontal rule and then the answer in one blob.
 * Split on the rule so each side can be laid out on its own.
 */
private fun plainTextSections(html: String): List<String> =
    HR.split(html)
        .map(::toPlainText)
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf("") }

private fun toPlainText(html: String): String {
    val withBreaks = LINE_BREAK.replace(SOUND_TAG.replace(html, ""), "\n")
    return unescapeEntities(HTML_TAG.replace(withBreaks, ""))
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private fun unescapeEntities(text: String): String {
    val decoded = NUMERIC_ENTITY.replace(text) { match ->
        val radix = if (match.groupValues[1].isEmpty()) 10 else 16
        match.groupValues[2].toIntOrNull(radix)
            ?.let { String(Character.toChars(it)) }
            ?: match.value
    }
    return NAMED_ENTITIES.fold(decoded) { acc, (entity, char) -> acc.replace(entity, char) }
}

private val HTML_TAG = Regex("<[^>]*>")
private val HTML_ENTITY = Regex("&[a-zA-Z]+;|&#[0-9]+;")

/**
 * Vocabulary cards are usually a single word, and a single word deserves to be
 * big. Longer cards step down so they still fit.
 */
private fun fontSizeFor(content: String): Int {
    val text = HTML_ENTITY.replace(HTML_TAG.replace(content, " "), " ").trim()
    return when {
        text.length <= 24 -> 44
        text.length <= 60 -> 32
        text.length <= 160 -> 25
        else -> 20
    }
}

private fun buildCardHtml(content: String, dark: Boolean): String {
    val fg = if (dark) "#ECEFF1" else "#1A1A1A"
    val bg = "transparent"
    val accent = if (dark) "#90CAF9" else "#1565C0"
    val fontSize = fontSizeFor(content)
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body {
                margin: 0; padding: 0; background: $bg; color: $fg;
                font-family: -apple-system, Roboto, sans-serif;
                font-size: ${fontSize}px; line-height: 1.35;
                -webkit-text-size-adjust: 100%;
            }
            body {
                /* Height follows the content: the view is sized around it and
                   the vertical centring is done by the layout, not the page. */
                display: flex; flex-direction: column; align-items: center;
                text-align: center;
                padding: 4px; box-sizing: border-box; word-wrap: break-word;
            }
            hr { border: none; border-top: 1px solid #9993; margin: 16px 0; width: 60%; }
            img { max-width: 100%; height: auto; }
            b, strong { color: $accent; }
            .cloze { color: $accent; font-weight: bold; }
        </style>
        </head>
        <body>$content</body>
        </html>
    """.trimIndent()
}
