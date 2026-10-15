package nl.gamegraph.app

import nl.gamegraph.app.crash.CrashCategory
import nl.gamegraph.app.crash.CrashReport
import nl.gamegraph.app.crash.CrashStore
import nl.gamegraph.app.crash.DeliberateTestCrash
import nl.gamegraph.app.crash.KeyValues
import nl.gamegraph.app.crash.LogSanitizer
import nl.gamegraph.app.crash.RecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

private class MapKeyValues : KeyValues {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class CrashRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- Privacy ----

    @Test fun sanitizerMasksSecrets() {
        val dirty = """
            java.lang.IllegalStateException: failed password=hunter2 token: abc123
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature
            api_key=ABCDEF0123456789ABCDEF0123456789ABCDEF
            ghp_abcdefghijklmnopqrstuvwxyz0123456789
            mail me at someone@example.com
            see https://example.com/path?session=secret&x=1
            file /data/user/0/nl.gamegraph.app/files/secret.json
        """.trimIndent()
        val clean = LogSanitizer.sanitize(dirty)
        listOf("hunter2", "abc123", "eyJhbGciOiJIUzI1NiJ9", "ABCDEF0123456789", "ghp_abcdef", "someone@example.com", "session=secret", "/data/user/0").forEach {
            assertFalse("'$it' moet verdwenen zijn uit:\n$clean", clean.contains(it))
        }
        assertTrue(clean.contains("IllegalStateException"))
        assertTrue(clean.contains("https://example.com/path"))
    }

    @Test fun sanitizerKeepsNormalStackFrames() {
        val frame = "at nl.gamegraph.app.ui.screens.DetailScreenKt.DetailScreen(DetailScreen.kt:42)"
        assertEquals(frame, LogSanitizer.sanitize(frame))
    }

    @Test fun reportsContainNoSecretsFromExceptionMessages() {
        val e = IllegalStateException("login failed, password=topsecret")
        val text = CrashReport.from(e, 1_700_000_000_000L).toText()
        assertFalse(text.contains("topsecret"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("Stacktrace"))
    }

    @Test fun reportsAreTrimmed() {
        fun deep(n: Int): Throwable = if (n == 0) RuntimeException("x") else RuntimeException("level $n", deep(n - 1))
        val lines = CrashReport.from(deep(30), 0).stackTrace.lines()
        assertTrue(lines.size <= CrashReport.MAX_LINES + 1)
    }

    // ---- Categorieën ----

    @Test fun categories() {
        assertEquals(CrashCategory.TEST, CrashReport.categorize(DeliberateTestCrash()))
        assertEquals(CrashCategory.MEMORY, CrashReport.categorize(OutOfMemoryError()))
        assertEquals(CrashCategory.STORAGE, CrashReport.categorize(RuntimeException("wrap", IOException("disk"))))
        assertEquals(CrashCategory.LOGIC, CrashReport.categorize(IllegalArgumentException("x")))
    }

    // ---- Opslag van rapporten ----

    @Test fun storeKeepsOnlyRecentReports() {
        val store = CrashStore(tmp.newFolder("crash"))
        assertNull(store.latest())
        for (i in 1..8) store.save(CrashReport.from(RuntimeException("crash $i"), 1000L + i))
        val files = tmp.root.resolve("crash").listFiles()!!
        assertEquals(CrashStore.KEEP, files.size)
        assertNotNull(store.latest())
        assertTrue(store.latest()!!.contains("GameGraph diagnostiekrapport"))
        store.clear()
        assertNull(store.latest())
    }

    // ---- Herstelstatus ----

    @Test fun crashFlagIsConsumedOnce() {
        val state = RecoveryState(MapKeyValues())
        assertFalse(state.consumeRecoveryNotice())
        state.recordCrash(1_000)
        assertTrue(state.consumeRecoveryNotice())
        assertFalse(state.consumeRecoveryNotice())
    }

    @Test fun repeatedCrashesBlockRetryLoops() {
        val state = RecoveryState(MapKeyValues())
        state.recordCrash(10_000)
        state.recordCrash(20_000)
        assertFalse("twee crashes is nog geen loop", state.isCrashLoop(30_000))
        state.recordCrash(30_000)
        assertTrue("drie crashes binnen twee minuten is een loop", state.isCrashLoop(40_000))
        assertFalse("na het venster is het weer vrij", state.isCrashLoop(10_000 + RecoveryState.WINDOW_MILLIS + 40_000))
    }

    @Test fun spreadOutCrashesAreNotALoop() {
        val state = RecoveryState(MapKeyValues())
        listOf(0L, 200_000L, 400_000L, 600_000L).forEach { state.recordCrash(it) }
        assertFalse(state.isCrashLoop(610_000))
    }

    @Test fun stableRunClearsHistoryAndResetClearsEverything() {
        val state = RecoveryState(MapKeyValues())
        repeat(3) { state.recordCrash(1_000L * (it + 1)) }
        state.lastSafeRoute = "library"
        state.markStable()
        assertFalse(state.isCrashLoop(5_000))
        assertEquals("library", state.lastSafeRoute)
        state.recordCrash(6_000)
        state.resetAll()
        assertNull(state.lastSafeRoute)
        assertFalse(state.consumeRecoveryNotice())
        assertTrue(state.crashTimes().isEmpty())
    }

    @Test fun corruptStoredValuesAreIgnored() {
        val kv = MapKeyValues().apply { map["crash_times"] = "abc,,12x,5000" }
        val state = RecoveryState(kv)
        assertEquals(listOf(5000L), state.crashTimes())
        assertFalse(state.isCrashLoop(6000))
    }
}
