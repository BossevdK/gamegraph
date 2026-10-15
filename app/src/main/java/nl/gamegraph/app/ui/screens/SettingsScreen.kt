package nl.gamegraph.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.gamegraph.app.AppState
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.BuildConfig
import nl.gamegraph.app.crash.DeliberateTestCrash
import nl.gamegraph.app.data.ThemeMode
import nl.gamegraph.app.update.DownloadState
import nl.gamegraph.app.update.InstallEvents
import nl.gamegraph.app.update.InstallOutcome
import nl.gamegraph.app.update.InstallPrecheck
import nl.gamegraph.app.update.UpdateCheckResult
import nl.gamegraph.app.update.UpdateInfo
import nl.gamegraph.app.update.UpdateManager
import nl.gamegraph.app.ui.formatBytes
import nl.gamegraph.app.ui.formatDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: AppState, vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by vm.updateState.collectAsState()
    val download by vm.updateManager.download.collectAsState()
    val installOutcome by InstallEvents.outcome.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCrash by remember { mutableStateOf(false) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFrom) }

    fun diagnosticsText(): String = buildString {
        appendLine("GameGraph diagnostiek")
        appendLine("Versie: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}), build: ${BuildConfig.DISTRIBUTION}")
        appendLine("Android API: ${android.os.Build.VERSION.SDK_INT}")
        appendLine("Laatste updatecontrole: ${formatDateTime(update.lastCheckAt)} — ${update.lastResult.ifBlank { "geen" }}")
        appendLine()
        append(vm.crashStore.latest() ?: "Geen crashrapport opgeslagen.")
    }
    val reportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(diagnosticsText().toByteArray()) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Instellingen") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Thema")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> FilterChip(state.settings.theme == m, { vm.setTheme(m) }, { Text(m.label) }) }
            }

            HorizontalDivider()
            Section("Updates")
            Text("Geïnstalleerd: ${BuildConfig.VERSION_NAME} (versiecode ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
            Text("Laatste controle: ${formatDateTime(update.lastCheckAt)}" + if (update.lastResult.isNotBlank()) " — ${update.lastResult}" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            update.installedNotice?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Update naar $it is geïnstalleerd.", Modifier.weight(1f))
                        TextButton(onClick = vm::dismissInstalledNotice) { Text("OK") }
                    }
                }
            }
            Button(onClick = vm::checkForUpdates, enabled = !update.checking) { Text(if (update.checking) "Controleren…" else "Controleer op updates") }
            when (val r = update.result) {
                is UpdateCheckResult.Available -> UpdateCard(r.info, download, onDownload = { vm.downloadUpdate(r.info); blockedMessage = null }, onInstall = { file ->
                    when (val pre = vm.updateManager.precheck(file)) {
                        is InstallPrecheck.Blocked -> blockedMessage = pre.message
                        InstallPrecheck.Ok ->
                            if (!vm.updateManager.canInstallPackages()) {
                                blockedMessage = "Android moet eerst toestemming geven om apps uit GameGraph te installeren. Zet \"Toestaan vanuit deze bron\" aan en kom terug om op Installeren te tikken."
                                context.startActivity(vm.updateManager.permissionSettingsIntent())
                            } else {
                                InstallEvents.outcome.value = null
                                vm.updateManager.install(file, r.info)
                            }
                    }
                })
                is UpdateCheckResult.Failed -> Text(UpdateManager.failureText(r.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                is UpdateCheckResult.UpToDate -> Text("GameGraph is up-to-date.", style = MaterialTheme.typography.bodyMedium)
                null -> Unit
            }
            blockedMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            when (val o = installOutcome) {
                InstallOutcome.Cancelled -> Text("Installatie geannuleerd. De huidige app blijft bruikbaar.", style = MaterialTheme.typography.bodyMedium)
                is InstallOutcome.Failed -> Text(o.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                else -> Unit
            }
            SwitchRow("Automatisch controleren (maximaal 1× per dag)", state.settings.autoCheckUpdates, vm::setAutoCheck)
            SwitchRow("Ook prereleases tonen", state.settings.includePrereleases, vm::setIncludePrereleases)
            Text(
                "Updates komen van github.com/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}. Android vraagt altijd zelf om bevestiging en accepteert alleen apps met dezelfde ondertekeningssleutel.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Section("Databeheer")
            Text("Alles wordt lokaal op dit toestel bewaard. Een export bevat je reacties, beoordelingen, favorieten en voorkeuren.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("gamegraph-export.json") }) { Text("Gegevens exporteren") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Gegevens importeren") }
                OutlinedButton(onClick = vm::clearCache) { Text("Cache wissen") }
            }
            Text("Cache wissen verwijdert gedownloade updates en tijdelijke bestanden, niet je database.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Al mijn gegevens wissen", color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            Section("Privacy")
            Text(
                "GameGraph heeft geen account, geen advertenties en geen verborgen telemetrie. Je smaakprofiel verlaat dit toestel nooit. " +
                    "Het enige netwerkverkeer is de updatecontrole bij GitHub en het openen van winkelpagina's wanneer jij dat kiest.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            Section("Diagnostiek")
            Text("Crashrapporten worden alleen lokaal bewaard en zijn ontdaan van gevoelige gegevens.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Rapport verbergen" else "Rapport bekijken") }
                OutlinedButton(onClick = { reportLauncher.launch("gamegraph-diagnostiek.txt") }) { Text("Rapport opslaan") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diagnosticsText()), "Rapport delen"
                    ))
                }) { Text("Rapport delen") }
            }
            if (showReport) Text(diagnosticsText(), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { confirmCrash = true }) { Text("Crashpagina testen") }

            HorizontalDivider()
            Section("Over GameGraph")
            Text("Versie ${BuildConfig.VERSION_NAME} · versiecode ${BuildConfig.VERSION_CODE} · build ${BuildConfig.DISTRIBUTION}", style = MaterialTheme.typography.bodyMedium)
            Text("Catalogus: demo-catalogus met ${state.games.size} games (niet compleet of actueel).", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Alle gegevens wissen?") },
        text = { Text("Je favorieten, wishlist, beoordelingen, voorkeuren en leerhistorie worden permanent verwijderd van dit toestel. Maak eerst een export als je ze wilt bewaren.") },
        confirmButton = { TextButton(onClick = { vm.deleteAllData(); confirmDelete = false }) { Text("Wissen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
    )
    if (confirmCrash) AlertDialog(
        onDismissRequest = { confirmCrash = false },
        title = { Text("Crashpagina testen?") },
        text = { Text("De app veroorzaakt opzettelijk een fout en toont daarna het herstelscherm. Er gaat geen data verloren.") },
        confirmButton = { TextButton(onClick = { confirmCrash = false; throw DeliberateTestCrash() }) { Text("Testfout veroorzaken") } },
        dismissButton = { TextButton(onClick = { confirmCrash = false }) { Text("Annuleren") } },
    )
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleLarge)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
private fun UpdateCard(info: UpdateInfo, download: DownloadState, onDownload: () -> Unit, onInstall: (java.io.File) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nieuwe versie: ${info.version}${if (info.prerelease) " (prerelease)" else ""}", style = MaterialTheme.typography.titleMedium)
            Text("Grootte: ${formatBytes(info.apkSize)} · ${if (info.sha256 != null) "met SHA-256-controle" else "zonder checksum in de release"}", style = MaterialTheme.typography.labelMedium)
            if (info.notes.isNotBlank()) Text(info.notes.take(1200), style = MaterialTheme.typography.bodyMedium)
            when (download) {
                is DownloadState.Running -> {
                    LinearProgressIndicator(progress = { if (download.total > 0) (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Downloaden… ${formatBytes(download.bytes)}", style = MaterialTheme.typography.labelMedium)
                }
                is DownloadState.Done -> Button(onClick = { onInstall(download.file) }) { Text("Installeren") }
                is DownloadState.Error -> {
                    Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text("Opnieuw proberen") }
                }
                DownloadState.Idle -> Button(onClick = onDownload) { Text("Downloaden") }
            }
        }
    }
}
