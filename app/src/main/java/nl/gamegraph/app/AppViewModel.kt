package nl.gamegraph.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.gamegraph.app.data.AppSettings
import nl.gamegraph.app.data.ImportResult
import nl.gamegraph.app.data.ThemeMode
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.DeckFilter
import nl.gamegraph.app.domain.FeedbackEvent
import nl.gamegraph.app.domain.FeedbackProcessor
import nl.gamegraph.app.domain.Game
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.domain.Recommendation
import nl.gamegraph.app.domain.Recommender
import nl.gamegraph.app.domain.TasteProfile
import nl.gamegraph.app.domain.UserGame
import nl.gamegraph.app.update.UpdateCheckResult
import nl.gamegraph.app.update.UpdateInfo
import nl.gamegraph.app.update.UpdateManager

data class AppState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val games: List<Game> = emptyList(),
    val gamesById: Map<String, Game> = emptyMap(),
    val user: Map<String, UserGame> = emptyMap(),
    val events: List<FeedbackEvent> = emptyList(),
    val manualAxes: Map<Axis, Float> = emptyMap(),
    val profile: TasteProfile = TasteProfile(),
    val settings: AppSettings = AppSettings(),
    val deck: List<Recommendation> = emptyList(),
    val forYou: List<Recommendation> = emptyList(),
    val discoveries: List<Recommendation> = emptyList(),
    val recent: List<Game> = emptyList(),
    val filter: DeckFilter = DeckFilter(),
) {
    fun userOf(id: String): UserGame = user[id] ?: UserGame(id)
    val allGenres: List<String> get() = games.flatMap { it.genres }.distinct().sorted()
    val allPlatforms: List<String> get() = games.flatMap { it.platforms }.distinct().sorted()
}

data class UpdateUi(
    val checking: Boolean = false,
    val result: UpdateCheckResult? = null,
    val lastCheckAt: Long = 0L,
    val lastResult: String = "",
    val installedNotice: String? = null,
)

class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repository
    private val settingsStore = container.settings
    private val clock: () -> Long = System::currentTimeMillis

    private val filter = MutableStateFlow(DeckFilter())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Gewone fouten en bevestigingen als korte melding (geen crashpagina). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _update = MutableStateFlow(UpdateUi(lastCheckAt = container.updates.lastCheckAt, lastResult = container.updates.lastResult))
    val updateState: StateFlow<UpdateUi> = _update

    private class Core(
        val cached: List<Game>, val user: Map<String, UserGame>, val events: List<FeedbackEvent>,
        val manual: Map<Axis, Float>, val settings: AppSettings,
    )

    /** Telt op zodra de basiscatalogus (opnieuw) is geladen; 0 = nog niet geladen. */
    private val catalogVersion = MutableStateFlow(0)
    @Volatile private var baseCatalog: List<Game> = emptyList()

    private val core = combine(
        repo.observeCachedGames(), repo.observeUserGames(), repo.observeEvents(), repo.observeManualAxes(), settingsStore.settings,
    ) { cached, user, events, manual, settings -> Core(cached, user, events, manual, settings) }

    val state: StateFlow<AppState> = combine(core, filter, catalogVersion) { c, f, version -> compute(c, f, version) }
        .flowOn(Dispatchers.Default)
        .catch { emit(AppState(loading = false, loadError = "De gegevens konden niet worden geladen. Probeer opnieuw of herstel de app.")) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState())

    private suspend fun loadCatalog() {
        baseCatalog = withContext(Dispatchers.Default) { repo.loadBaseCatalog() }
        catalogVersion.update { it + 1 }
    }

    init {
        viewModelScope.launch {
            loadCatalog()
            if (container.databaseWasRecovered) _messages.tryEmit("Een beschadigde database is opgevangen; een kopie is bewaard als gamegraph.db.corrupt.")
        }
        viewModelScope.launch {
            _update.update { it.copy(installedNotice = container.updates.consumeInstalledUpdate()) }
            val s = settingsStore.settings.first()
            val dayMs = 24L * 60 * 60 * 1000
            if (s.autoCheckUpdates && clock() - container.updates.lastCheckAt > dayMs) checkForUpdates()
        }
    }

    private fun compute(core: Core, f: DeckFilter, version: Int): AppState {
        if (version == 0) return AppState(loading = true, settings = core.settings, filter = f)
        val byId = LinkedHashMap<String, Game>()
        baseCatalog.forEach { byId[it.id] = it }
        core.cached.forEach { byId[it.id] = it }
        val games = byId.values.sortedBy { it.title }

        val learning = core.user.filterValues { it.updatedAt > core.settings.profileResetAt }
        val profile = TasteProfile.from(learning.values, core.manual, byId)
        val undecided = games.filter { core.user[it.id]?.hasDecision != true }
        val deck = Recommender.buildDeck(undecided.filter { f.accepts(it) }, profile, 20)
        val forYou = Recommender.rank(undecided, profile).take(10)
        val seen = forYou.map { it.game.id }.toSet()
        val discoveries = Recommender.rank(undecided.filter { it.id !in seen }, profile)
            .sortedByDescending { it.novelty + it.score * 0.5f }.take(10)
        val recent = core.user.values.filter { it.lastViewedAt != null }.sortedByDescending { it.lastViewedAt }
            .mapNotNull { byId[it.gameId] }.take(10)
        return AppState(
            loading = false, games = games, gamesById = byId, user = core.user,
            events = core.events.filter { it.createdAt > core.settings.profileResetAt },
            manualAxes = core.manual, profile = profile, settings = core.settings,
            deck = deck, forYou = forYou, discoveries = discoveries, recent = recent, filter = f,
        )
    }

    fun retryLoad() { viewModelScope.launch { loadCatalog() } }

    private fun safely(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _messages.tryEmit("$label is niet gelukt. Je gegevens zijn niet gewijzigd.")
            }
        }
    }

    fun setFilter(f: DeckFilter) { filter.value = f }

    fun react(id: String, reaction: Reaction) = safely("Opslaan") { repo.setReaction(id, reaction) }
    fun setSaved(id: String, on: Boolean) = safely("Opslaan") { repo.setSaved(id, on) }
    fun setFavorite(id: String, on: Boolean) = safely("Opslaan") { repo.setFavorite(id, on) }
    fun setWishlist(id: String, on: Boolean) = safely("Opslaan") { repo.setWishlist(id, on) }
    fun setRating(id: String, rating: Int?) = safely("Beoordelen") { repo.setRating(id, rating) }
    fun markViewed(id: String) = safely("Bijwerken") { repo.markViewed(id) }
    fun setAxis(axis: Axis, value: Float?) = safely("Opslaan") { repo.setAxis(axis, value) }

    fun resetProfile() = safely("Opnieuw instellen") {
        repo.resetProfile()
        settingsStore.setProfileResetAt(clock())
        _messages.tryEmit("Profiel en leerhistorie zijn opnieuw ingesteld. Je bibliotheek is behouden.")
    }

    fun deleteAllData() = safely("Wissen") {
        repo.deleteAllUserData()
        settingsStore.setProfileResetAt(0L)
        _messages.tryEmit("Alle gegevens zijn gewist.")
    }

    fun clearCache() = safely("Cache wissen") {
        withContext(Dispatchers.IO) {
            container.updates.clearDownloads()
            container.context.cacheDir.deleteRecursively()
        }
        repo.clearCachedGames()
        _messages.tryEmit("Cache gewist. Je favorieten, wishlist en profiel zijn niet aangeraakt.")
    }

    fun setTheme(mode: ThemeMode) = safely("Thema") { settingsStore.setTheme(mode) }
    fun setIncludePrereleases(on: Boolean) = safely("Instelling") { settingsStore.setIncludePrereleases(on) }
    fun setAutoCheck(on: Boolean) = safely("Instelling") { settingsStore.setAutoCheck(on) }

    fun exportTo(uri: Uri) = safely("Exporteren") {
        withContext(Dispatchers.IO) {
            val text = repo.export(state.value.settings.profileResetAt)
            container.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                ?: error("Kon het bestand niet openen")
        }
        _messages.tryEmit("Gegevens geëxporteerd.")
    }

    fun importFrom(uri: Uri) = safely("Importeren") {
        val text = withContext(Dispatchers.IO) {
            container.context.contentResolver.openInputStream(uri)?.use { s ->
                // Een export is klein; alles boven 5 MB is vrijwel zeker geen GameGraph-bestand.
                val limit = 5 * 1024 * 1024
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var tooBig = false
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) { tooBig = true; break }
                }
                if (tooBig) null else out.toString(Charsets.UTF_8.name())
            }
        }
        if (text == null) { _messages.tryEmit("Dit bestand is te groot of onleesbaar."); return@safely }
        val (result, resetAt) = repo.import(text)
        when (result) {
            is ImportResult.Success -> {
                settingsStore.setProfileResetAt(resetAt)
                _messages.tryEmit("Geïmporteerd: ${result.games} games en ${result.events} reacties.")
            }
            is ImportResult.Invalid -> _messages.tryEmit(result.reason)
        }
    }

    fun checkForUpdates() {
        if (_update.value.checking) return
        viewModelScope.launch {
            _update.update { it.copy(checking = true) }
            val include = state.value.settings.includePrereleases
            val result = container.updates.check(include)
            _update.update { it.copy(checking = false, result = result, lastCheckAt = container.updates.lastCheckAt, lastResult = container.updates.lastResult) }
        }
    }

    fun dismissInstalledNotice() { _update.update { it.copy(installedNotice = null) } }

    val updateManager: UpdateManager get() = container.updates
    val recovery get() = container.recovery
    val crashStore get() = container.crashStore

    fun downloadUpdate(info: UpdateInfo) {
        viewModelScope.launch { container.updates.download(info) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}
