package nl.gamegraph.app

import android.app.Application
import android.content.Context
import nl.gamegraph.app.crash.CrashHandler
import nl.gamegraph.app.crash.CrashStore
import nl.gamegraph.app.crash.RecoveryState
import nl.gamegraph.app.data.GameDatabase
import nl.gamegraph.app.data.GameRepository
import nl.gamegraph.app.data.SeedGameSource
import nl.gamegraph.app.data.SettingsStore
import nl.gamegraph.app.update.UpdateManager

/**
 * Eenvoudige handmatige dependency injection. Nieuwe databronnen voeg je toe in [repository] (zie README, "Een databron toevoegen").
 */
class AppContainer(val context: Context) {
    /** Wordt waar als een beschadigde database is opgevangen; de UI meldt dat eenmalig. */
    @Volatile var databaseWasRecovered = false

    val database: GameDatabase by lazy { GameDatabase.open(context) { databaseWasRecovered = true } }
    val repository: GameRepository by lazy { GameRepository(database, listOf(SeedGameSource())) }
    val settings: SettingsStore by lazy { SettingsStore(context) }
    val updates: UpdateManager by lazy { UpdateManager(context) }
    val recovery: RecoveryState by lazy { RecoveryState.forContext(context) }
    val crashStore: CrashStore by lazy { CrashStore.forContext(context) }
}

class GameGraphApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Het herstelproces (:recovery) mag geen crashhandler of database starten; dat proces moet simpel en veilig blijven.
        if (!CrashHandler.isRecoveryProcess()) CrashHandler.install(this)
    }
}
