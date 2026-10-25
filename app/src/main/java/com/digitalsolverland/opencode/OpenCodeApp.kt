package com.digitalsolverland.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TermGreen = Color(0xFF3DDC84)
private val TermBg = Color(0xFF0B0F14)
private val TermRed = Color(0xFFFF6B6B)
private val TermGray = Color(0xFF9AA5B1)

private val DevColors = darkColorScheme(
    primary = TermGreen,
    onPrimary = Color.Black,
    background = Color(0xFF0E1116),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE6EDF3),
    onBackground = Color(0xFFE6EDF3),
    secondaryContainer = Color(0xFF1E2630),
    onSecondaryContainer = Color(0xFFE6EDF3)
)

@Composable
fun OpenCodeApp(
    settings: SecureSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStatus: () -> Unit,
    onSend: (String) -> Unit
) {
    MaterialTheme(colorScheme = DevColors) {
        var showSettings by remember { mutableStateOf(false) }
        if (showSettings) {
            SettingsScreen(settings, onBack = { showSettings = false })
        } else {
            DashboardScreen(settings, onStart, onStop, onStatus, onSend, onOpenSettings = { showSettings = true })
        }
    }
}

// ============================================================ DASHBOARD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    settings: SecureSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStatus: () -> Unit,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val logs by AgentBus.logs.collectAsState()
    val status by AgentBus.status.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val quickActions = remember { settings.quickActions() }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("OpenCode Mobile", fontFamily = FontFamily.Monospace) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .imePadding()
        ) {
            // --- Barre de statut + contrôle
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(10.dp).background(
                        when (status) {
                            AgentStatus.RUNNING -> TermGreen
                            AgentStatus.STARTING -> Color(0xFFFFC857)
                            AgentStatus.ERROR -> TermRed
                            AgentStatus.STOPPED -> TermGray
                        }, CircleShape
                    )
                )
                Text(
                    "  ${status.name} · :${settings.port}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (status == AgentStatus.RUNNING || status == AgentStatus.STARTING) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = onStart) { Text("Start") }
                }
            }

            // --- Terminal / logs
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TermBg, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(state = listState) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = when {
                                line.startsWith("> ") -> TermGreen
                                line.startsWith("[err]") -> TermRed
                                line.startsWith("[sys]") -> TermGray
                                else -> Color(0xFFD7DEE6)
                            }
                        )
                    }
                }
            }

            // --- Actions rapides
            LazyRow(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickActions) { (label, prompt) ->
                    AssistChip(
                        onClick = {
                            // "Vérifier le statut" fait aussi un ping HTTP localhost
                            if (label.contains("statut", ignoreCase = true)) onStatus()
                            onSend(prompt)
                        },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
                item {
                    AssistChip(onClick = { AgentBus.clear() }, label = { Text("Clear", fontSize = 12.sp) })
                }
            }

            // --- Saisie
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message / commande…") },
                    maxLines = 4
                )
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSend(input.trim())
                            input = ""
                        }
                    },
                    enabled = status == AgentStatus.RUNNING
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer", tint = TermGreen)
                }
            }
        }
    }
}

// ============================================================ SETTINGS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SecureSettings, onBack: () -> Unit) {
    var openAi by remember { mutableStateOf(settings.openAiKey) }
    var openRouter by remember { mutableStateOf(settings.openRouterKey) }
    var anthropic by remember { mutableStateOf(settings.anthropicKey) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var entry by remember { mutableStateOf(settings.entryScript) }
    var args by remember { mutableStateOf(settings.extraArgs) }
    var autoStart by remember { mutableStateOf(settings.autoStartOnBoot) }
    var quick by remember { mutableStateOf(settings.quickActionsRaw) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Clés API (chiffrées AES-256)", color = TermGreen, fontSize = 13.sp)
            SecretField("OpenAI API key", openAi) { openAi = it }
            SecretField("OpenRouter API key", openRouter) { openRouter = it }
            SecretField("Anthropic API key", anthropic) { anthropic = it }

            Text("Agent", color = TermGreen, fontSize = 13.sp)
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port local") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = entry, onValueChange = { entry = it },
                label = { Text("Script d'entrée (relatif à filesDir)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = args, onValueChange = { args = it },
                label = { Text("Arguments ({PORT} = port)") }, modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Démarrer au boot", modifier = Modifier.weight(1f))
                Switch(checked = autoStart, onCheckedChange = { autoStart = it })
            }

            Text("Actions rapides (Libellé::Prompt, une par ligne)", color = TermGreen, fontSize = 13.sp)
            OutlinedTextField(
                value = quick, onValueChange = { quick = it },
                modifier = Modifier.fillMaxWidth(), minLines = 4
            )

            Button(
                onClick = {
                    settings.openAiKey = openAi.trim()
                    settings.openRouterKey = openRouter.trim()
                    settings.anthropicKey = anthropic.trim()
                    settings.port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 4096
                    settings.entryScript = entry.trim()
                    settings.extraArgs = args.trim()
                    settings.autoStartOnBoot = autoStart
                    settings.quickActionsRaw = quick
                    saved = true
                },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enregistrer") }

            if (saved) {
                Text(
                    "Enregistré. Redémarre l'agent (et l'app pour les actions rapides) pour appliquer.",
                    fontSize = 12.sp, color = TermGray
                )
            }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
}
