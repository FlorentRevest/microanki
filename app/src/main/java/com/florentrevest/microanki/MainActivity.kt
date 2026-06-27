package com.florentrevest.microanki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup + configuration screen: grant permissions, pick a deck, and choose
 * which apps should trigger a flashcard when opened.
 */
class MainActivity : ComponentActivity() {

    private val anki by lazy { AnkiDroidHelper(this) }
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = appColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen()
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val context = LocalContext.current
        val scroll = rememberScrollState()
        val tick = resumeTick()

        // Re-read live status whenever we come back to the foreground.
        val apiAvailable = remember(tick) { anki.isApiAvailable() }
        val hasPermission = remember(tick) { anki.hasPermission() }
        val accessibilityOn = remember(tick) { AppMonitorService.isEnabled(context) }
        val canOverlay = remember(tick) { Settings.canDrawOverlays(context) }

        val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(scroll)
                .padding(20.dp),
        ) {
            Text("MicroAnki", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "See a flashcard every time you open a distracting app. " +
                    "Practise your vocabulary before you scroll.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))

            SectionCard("1 · Permissions") {
                StatusRow(
                    label = "AnkiDroid installed",
                    done = apiAvailable,
                    actionLabel = "Get",
                    onAction = { openAnkiDroidInStore(context) },
                )
                StatusRow(
                    label = "Access to AnkiDroid collection",
                    done = hasPermission,
                    actionLabel = "Grant",
                    enabled = apiAvailable,
                    onAction = { permissionLauncher.launch(AnkiDroidHelper.READ_WRITE_PERMISSION) },
                )
                StatusRow(
                    label = "Detect app launches (accessibility)",
                    done = accessibilityOn,
                    actionLabel = "Enable",
                    onAction = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
                StatusRow(
                    label = "Display over other apps",
                    done = canOverlay,
                    actionLabel = "Allow",
                    onAction = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            SectionCard("2 · Deck to practise") {
                DeckPicker(enabled = hasPermission)
            }

            Spacer(Modifier.height(16.dp))

            SectionCard("3 · Apps that trigger a card") {
                Text(
                    "When you open one of these, a card appears first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TriggerAppList()
            }

            Spacer(Modifier.height(16.dp))

            SectionCard("4 · Options") {
                CooldownField()
                Spacer(Modifier.height(8.dp))
                ForceAnswerSwitch()
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { startActivity(Intent(this@MainActivity, FlashcardActivity::class.java)) },
                enabled = hasPermission && prefs.hasDeck,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show a card now") }

            Spacer(Modifier.height(24.dp))
        }
    }

    // --- Sections -----------------------------------------------------------

    @Composable
    private fun DeckPicker(enabled: Boolean) {
        val scope = rememberCoroutineScopeCompat()
        var decks by remember { mutableStateOf<List<DeckInfo>>(emptyList()) }
        var expanded by remember { mutableStateOf(false) }
        var deckName by remember { mutableStateOf(prefs.deckName) }

        Column {
            OutlinedButton(
                enabled = enabled,
                onClick = {
                    scope.launch {
                        decks = withContext(Dispatchers.IO) { anki.getDecks() }
                        expanded = true
                    }
                },
            ) {
                Text(if (deckName.isBlank()) "Choose a deck" else "Deck: $deckName")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (decks.isEmpty()) {
                    DropdownMenuItem(text = { Text("No decks found") }, onClick = { expanded = false })
                }
                decks.forEach { deck ->
                    DropdownMenuItem(
                        text = { Text(deck.name) },
                        onClick = {
                            prefs.deckId = deck.id
                            prefs.deckName = deck.name
                            deckName = deck.name
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun TriggerAppList() {
        val context = LocalContext.current
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(prefs.triggerPackages) }

        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
            loading = false
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return
        }

        val filtered = apps.filter { it.label.contains(query, ignoreCase = true) }
        filtered.forEach { app ->
            val checked = app.packageName in selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                app.icon?.let {
                    Image(bitmap = it, contentDescription = null, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Checkbox(
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        selected = if (isChecked) selected + app.packageName else selected - app.packageName
                        prefs.triggerPackages = selected
                    },
                )
            }
        }
    }

    @Composable
    private fun CooldownField() {
        var text by remember { mutableStateOf(prefs.cooldownMinutes.toString()) }
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new.filter { it.isDigit() }.take(4)
                prefs.cooldownMinutes = text.toIntOrNull() ?: 0
            },
            label = { Text("Minimum minutes between cards (0 = every open)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun ForceAnswerSwitch() {
        var force by remember { mutableStateOf(prefs.forceAnswer) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Block back until answered", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Makes the card harder to dismiss without engaging.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = force,
                onCheckedChange = {
                    force = it
                    prefs.forceAnswer = it
                },
            )
        }
    }

    // --- Small reusable pieces ---------------------------------------------

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun StatusRow(
        label: String,
        done: Boolean,
        actionLabel: String,
        enabled: Boolean = true,
        onAction: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            val icon: ImageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (done) {
                Text("Done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
            }
        }
        HorizontalDivider()
    }

    private fun openAnkiDroidInStore(context: android.content.Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.ichi2.anki"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.ichi2.anki"))
        try {
            context.startActivity(market)
        } catch (e: Exception) {
            context.startActivity(web)
        }
    }
}

private data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

private fun loadLaunchableApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = pm.queryIntentActivities(intent, 0)
    return resolveInfos
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = ri.loadLabel(pm).toString()
            val icon = runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            AppEntry(pkg, label, icon)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

// --- Compose helpers --------------------------------------------------------

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

/** Increments every time the host lifecycle reaches ON_RESUME. */
@Composable
private fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}
