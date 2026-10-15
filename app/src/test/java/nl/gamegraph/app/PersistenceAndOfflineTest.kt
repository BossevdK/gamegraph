package nl.gamegraph.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.gamegraph.app.data.CachedGameEntity
import nl.gamegraph.app.data.GameDatabase
import nl.gamegraph.app.data.GameRepository
import nl.gamegraph.app.data.ImportResult
import nl.gamegraph.app.data.SeedCatalog
import nl.gamegraph.app.data.SeedGameSource
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.FeedbackType
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.update.HttpFetcher
import nl.gamegraph.app.update.HttpResult
import nl.gamegraph.app.update.UpdateCheckResult
import nl.gamegraph.app.update.UpdateFailure
import nl.gamegraph.app.update.UpdateManager
import nl.gamegraph.app.update.UpdateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceAndOfflineTest {
    private lateinit var context: Context
    private lateinit var db: GameDatabase
    private lateinit var repo: GameRepository
    private var now = 1_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = GameDatabase.inMemory(context)
        repo = GameRepository(db, listOf(SeedGameSource())) { now++ }
    }

    @After fun tearDown() { db.close() }

    // ---- Database ----

    @Test fun reactionsAreStoredWithHistory() = runBlocking {
        repo.setReaction("hades", Reaction.LIKE)
        repo.setFavorite("hades", true)
        repo.setRating("hades", 5)
        repo.setSaved("celeste", true)
        repo.setReaction("sekiro", Reaction.DISLIKE)

        val user = repo.observeUserGames().first()
        assertEquals(Reaction.LIKE, user.getValue("hades").reaction)
        assertTrue(user.getValue("hades").favorite)
        assertEquals(5, user.getValue("hades").rating)
        assertTrue(user.getValue("celeste").saved)
        assertEquals(Reaction.DISLIKE, user.getValue("sekiro").reaction)

        val types = repo.observeEvents().first().map { it.type }
        assertTrue(types.containsAll(listOf(FeedbackType.LIKE, FeedbackType.FAVORITE, FeedbackType.RATING, FeedbackType.SAVE, FeedbackType.DISLIKE)))
    }

    @Test fun togglingBackDoesNotAddLearningEvents() = runBlocking {
        repo.setReaction("hades", Reaction.LIKE)
        repo.setReaction("hades", Reaction.NONE)
        assertEquals(1, repo.observeEvents().first().size)
        assertEquals(Reaction.NONE, repo.observeUserGames().first().getValue("hades").reaction)
    }

    @Test fun viewingIsNotADecision() = runBlocking {
        repo.markViewed("hades")
        val u = repo.observeUserGames().first().getValue("hades")
        assertFalse(u.hasDecision)
        assertEquals(true, u.lastViewedAt != null)
        assertTrue(repo.observeEvents().first().isEmpty())
    }

    @Test fun manualAxesAreClampedAndRemovable() = runBlocking {
        repo.setAxis(Axis.STORY, 1.7f)
        assertEquals(1f, repo.observeManualAxes().first().getValue(Axis.STORY), 0f)
        repo.setAxis(Axis.STORY, null)
        assertTrue(repo.observeManualAxes().first().isEmpty())
    }

    @Test fun resetProfileKeepsLibraryButClearsHistoryAndPreferences() = runBlocking {
        repo.setFavorite("hades", true)
        repo.setAxis(Axis.COMBAT, 0.9f)
        repo.resetProfile()
        assertTrue(repo.observeEvents().first().isEmpty())
        assertTrue(repo.observeManualAxes().first().isEmpty())
        assertTrue(repo.observeUserGames().first().getValue("hades").favorite)
    }

    // ---- Export en import ----

    @Test fun exportImportRoundTrip() = runBlocking {
        repo.setReaction("hades", Reaction.LIKE)
        repo.setRating("celeste", 4)
        repo.setAxis(Axis.COOP, 0.2f)
        val text = repo.export(profileResetAt = 123)

        repo.deleteAllUserData()
        assertTrue(repo.observeUserGames().first().isEmpty())

        val (result, resetAt) = repo.import(text)
        assertEquals(123L, resetAt)
        assertTrue(result is ImportResult.Success)
        assertEquals(Reaction.LIKE, repo.observeUserGames().first().getValue("hades").reaction)
        assertEquals(4, repo.observeUserGames().first().getValue("celeste").rating)
        assertEquals(0.2f, repo.observeManualAxes().first().getValue(Axis.COOP), 0.0001f)
    }

    @Test fun invalidImportChangesNothing() = runBlocking {
        repo.setReaction("hades", Reaction.LIKE)
        listOf(
            "dit is geen json",
            """{"format":"iets-anders","exportedAt":1,"userGames":[],"events":[],"dnaPrefs":{}}""",
            """{"format":"gamegraph-export","version":9,"exportedAt":1,"userGames":[],"events":[],"dnaPrefs":{}}""",
            """{"format":"gamegraph-export","exportedAt":1,"userGames":[{"gameId":"x","rating":9}],"events":[],"dnaPrefs":{}}""",
            """{"format":"gamegraph-export","exportedAt":1,"userGames":[{"gameId":"x","reaction":"MAYBE"}],"events":[],"dnaPrefs":{}}""",
            """{"format":"gamegraph-export","exportedAt":1,"userGames":[],"events":[],"dnaPrefs":{"NOPE":0.5}}""",
        ).forEach { bad ->
            val (result, _) = repo.import(bad)
            assertTrue("moet geweigerd worden: $bad", result is ImportResult.Invalid)
        }
        assertEquals(Reaction.LIKE, repo.observeUserGames().first().getValue("hades").reaction)
    }

    // ---- Beschadigde of externe gegevens ----

    @Test fun corruptCachedGamesAreSkippedNotFatal() = runBlocking {
        val good = SeedCatalog.games.first().copy(id = "cached-1", title = "Cached game")
        repo.saveCachedGames(listOf(good), "test-bron")
        db.cachedGames().upsertAll(listOf(CachedGameEntity("broken", "{ dit is kapot", "test-bron", 1L)))
        val games = repo.observeCachedGames().first()
        assertEquals(listOf("cached-1"), games.map { it.id })
        repo.clearCachedGames()
        assertTrue(repo.observeCachedGames().first().isEmpty())
    }

    @Test fun corruptDatabaseFileIsRecoveredAndKeptAsBackup() {
        val file = context.getDatabasePath(GameDatabase.FILE_NAME)
        file.parentFile!!.mkdirs()
        file.writeText("dit is geen sqlite-database, het bestand is beschadigd".repeat(50))
        var reported = false
        val opened = GameDatabase.open(context) { reported = true }
        try {
            assertTrue(reported)
            assertTrue(java.io.File(file.parentFile, "${GameDatabase.FILE_NAME}.corrupt").exists())
            runBlocking { assertTrue(opened.userGames().getAll().isEmpty()) }
        } finally {
            opened.close()
            context.deleteDatabase(GameDatabase.FILE_NAME)
        }
    }

    // ---- Offline ----

    private class FakeFetcher(val result: () -> HttpResult) : HttpFetcher {
        var calls = 0
        override fun get(url: String): HttpResult { calls++; return result() }
    }

    private fun manager(fetcher: HttpFetcher) =
        UpdateManager(context, fetcher, UpdateStore(context.getSharedPreferences("test-updates", Context.MODE_PRIVATE)), clock = { 5_000L })

    @Test fun offlineUpdateCheckFailsGracefully() = runBlocking {
        val m = manager(FakeFetcher { throw UnknownHostException("api.github.com") })
        val r = m.check(false) as UpdateCheckResult.Failed
        assertEquals(UpdateFailure.NETWORK, r.reason)
        assertEquals(5_000L, m.lastCheckAt)
        assertTrue(m.lastResult.startsWith("Mislukt"))
    }

    @Test fun otherNetworkErrorsAreHandled() = runBlocking {
        assertEquals(UpdateFailure.NETWORK, (manager(FakeFetcher { throw IOException("reset") }).check(false) as UpdateCheckResult.Failed).reason)
        assertEquals(UpdateFailure.INVALID_DATA, (manager(FakeFetcher { throw IllegalStateException("kapot") }).check(false) as UpdateCheckResult.Failed).reason)
    }

    @Test fun httpErrorsAreMapped() = runBlocking {
        suspend fun reason(code: Int, headers: Map<String, String> = emptyMap(), body: String? = "") =
            (manager(FakeFetcher { HttpResult(code, body, headers) }).check(false) as UpdateCheckResult.Failed).reason
        assertEquals(UpdateFailure.NOT_FOUND, reason(404))
        assertEquals(UpdateFailure.RATE_LIMITED, reason(403, mapOf("x-ratelimit-remaining" to "0")))
        assertEquals(UpdateFailure.RATE_LIMITED, reason(429))
        assertEquals(UpdateFailure.SERVER, reason(500))
        assertEquals(UpdateFailure.SERVER, reason(403))
        assertEquals(UpdateFailure.INVALID_DATA, reason(200, body = "<html>geen json</html>"))
        assertEquals(UpdateFailure.INVALID_DATA, reason(200, body = null))
        assertEquals(UpdateFailure.NO_RELEASES, reason(200, body = "[]"))
    }

    @Test fun invalidApkFilesAreBlockedBeforeInstall() {
        val m = manager(FakeFetcher { HttpResult(200, "[]") })
        val file = java.io.File(context.cacheDir, "x.apk").apply { writeText("x") }
        assertTrue(m.precheck(file) is nl.gamegraph.app.update.InstallPrecheck.Blocked)
    }

    @Test fun appCoreWorksWithoutNetwork() = runBlocking {
        // Geen enkele netwerkafhankelijkheid: catalogus, aanbevelingen en opslag werken zonder fetcher.
        val catalog = repo.loadBaseCatalog()
        assertTrue(catalog.isNotEmpty())
        repo.setReaction(catalog.first().id, Reaction.LIKE)
        assertNull(null)
        assertEquals(1, repo.observeEvents().first().size)
    }
}
