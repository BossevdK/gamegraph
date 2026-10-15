package nl.gamegraph.app.crash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.gamegraph.app.BuildConfig
import nl.gamegraph.app.MainActivity
import nl.gamegraph.app.ui.theme.GameGraphTheme
import nl.gamegraph.app.update.DownloadState
import nl.gamegraph.app.update.InstallEvents
import nl.gamegraph.app.update.InstallOutcome
import nl.gamegraph.app.update.InstallPrecheck
import nl.gamegraph.app.update.UpdateCheckResult
import nl.gamegraph.app.update.UpdateInfo
import nl.gamegraph.app.update.UpdateManager
import java.io.File

/** Veilige herstelacties. Raken de database met gebruikersgegevens bewust NIET aan. */
object RecoveryActions {
    fun safeReset(context: Context) {
        runCatching { File(context.filesDir, "datastore").deleteRecursively() }
        runCatching { context.cacheDir.deleteRecursively() }
        runCatching { context.getExternalFilesDir(null)?.let { File(it, "updates").deleteRecursively() } }
        RecoveryState.forContext(context).resetAll()
    }
}

/**
 * Het crashscherm. Draait in een apart proces (`:recovery`), zodat het zelfs werkt als het hoofdproces
 * in een onbruikbare staat was. Het opent geen Room-database.
 */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recovery = RecoveryState.forContext(this)
        val report = CrashStore.forContext(this).latest()
        val loop = recovery.isCrashLoop(System.currentTimeMillis())
        setContent {
            GameGraphTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CrashScreen(
                        report = report ?: "Geen rapport beschikbaar.",
                        crashLoop = loop,
                        onRetry = { openMain(null) },
                        onHome = { recovery.lastSafeRoute = "home"; openMain("home") },
                        onRestore = { RecoveryActions.safeReset(this@CrashActivity) },
                        onShare = {
                            startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "GameGraph diagnostiekrapport")
                                    .putExtra(Intent.EXTRA_TEXT, report.orEmpty()), "Rapport delen"
                            ))
                        },
                        onClose = { finishAffinity() },
                    )
                }
            }
        }
    }

    private fun openMain(route: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        route?.let { intent.putExtra(MainActivity.EXTRA_ROUTE, it) }
        startActivity(intent)
        finish()
    }
}

@Composable
private fun CrashScreen(
    report: String,
    crashLoop: Boolean,
    onRetry: () -> Unit,
    onHome: () -> Unit,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { UpdateManager(context) }
    var showLogs by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val download by manager.download.collectAsState()
    val outcome by InstallEvents.outcome.collectAsState()

    BackHandler { onClose() }
    LaunchedEffect(outcome) {
        when (val o = outcome) {
            InstallOutcome.Cancelled -> message = "Installatie geannuleerd. De huidige app blijft bruikbaar."
            is InstallOutcome.Failed -> message = o.message
            InstallOutcome.Success, null -> Unit
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(48.dp))
        Text("Er is iets misgegaan", style = MaterialTheme.typography.headlineMedium)
        Text(
            "GameGraph is onverwacht gestopt. Je bewaarde games en beoordelingen staan veilig op dit toestel. " +
                "Probeer het opnieuw, of herstel de app als het probleem blijft terugkomen.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (crashLoop) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "De app is meerdere keren kort achter elkaar gestopt. Opnieuw proberen is daarom uitgeschakeld. " +
                        "Kies eerst \"App herstellen\" of controleer op een update.",
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Button(onClick = onRetry, enabled = !crashLoop, modifier = Modifier.fillMaxWidth()) { Text("Opnieuw proberen") }
        OutlinedButton(onClick = { onRestore(); restored = true; message = "App hersteld: voorkeuren en tijdelijke bestanden zijn gewist. Je gegevens zijn behouden." }, modifier = Modifier.fillMaxWidth()) {
            Text(if (restored) "App hersteld ✓" else "App herstellen")
        }
        OutlinedButton(onClick = { showLogs = !showLogs }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showLogs) "Logs verbergen" else "Logs bekijken")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    checking = true; message = null; update = null
                    when (val r = manager.check(includePrereleases = false)) {
                        is UpdateCheckResult.Available -> { update = r.info; message = "Update beschikbaar: ${r.info.tag}" }
                        else -> message = UpdateManager.describe(r)
                    }
                    checking = false
                }
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) "Controleren…" else "Update controleren") }

        update?.let { info ->
            Button(
                onClick = {
                    scope.launch {
                        manager.download(info)
                        val state = manager.download.value
                        if (state is DownloadState.Done) {
                            when (val pre = manager.precheck(state.file)) {
                                is InstallPrecheck.Blocked -> message = pre.message
                                InstallPrecheck.Ok ->
                                    if (!manager.canInstallPackages()) {
                                        message = "Android vraagt eerst toestemming om apps uit deze bron te installeren. Zet die aan en tik daarna opnieuw op Update downloaden."
                                        context.startActivity(manager.permissionSettingsIntent())
                                    } else manager.install(state.file, info)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Update downloaden") }
        }
        when (val d = download) {
            is DownloadState.Running -> LinearProgressIndicator(
                progress = { if (d.total > 0) (d.bytes.toFloat() / d.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            is DownloadState.Error -> Text(d.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        OutlinedButton(onClick = onHome, enabled = !crashLoop, modifier = Modifier.fillMaxWidth()) { Text("Terug naar home") }

        if (showLogs) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gevoelige gegevens zijn verwijderd. Er wordt niets automatisch verzonden.", style = MaterialTheme.typography.labelMedium)
                    Text(report, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    OutlinedButton(onClick = onShare) { Text("Rapport delen") }
                }
            }
        }
        Text("GameGraph ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
