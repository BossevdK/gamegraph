package nl.gamegraph.app.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import nl.gamegraph.app.BuildConfig
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

/** Haalt gevoelige gegevens uit tekst voordat die wordt opgeslagen, getoond of gedeeld. */
object LogSanitizer {
    private val secretPair = Regex("""(?i)\b(password|passwd|pwd|passphrase|token|secret|api[-_ ]?key|authorization|cookie|signature)\b(\s*[:=]\s*)("[^"]*"|'[^']*'|\S+)""")
    private val bearer = Regex("""(?i)\bbearer\s+\S+""")
    private val githubToken = Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b""")
    private val longSecret = Regex("""\b[A-Za-z0-9+/_\-]{32,}={0,2}""")
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val appPath = Regex("""/(?:data/(?:user/\d+|data)|storage/emulated/\d+|sdcard)/[^\s:)\]]*""")
    private val urlQuery = Regex("""(https?://[^\s?#]+)\?[^\s]*""")

    fun sanitize(text: String): String {
        var t = text
        t = bearer.replace(t, "Bearer [verborgen]")
        t = secretPair.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}[verborgen]" }
        t = githubToken.replace(t, "[verborgen]")
        t = email.replace(t, "[e-mail verborgen]")
        t = urlQuery.replace(t) { "${it.groupValues[1]}?[verborgen]" }
        t = appPath.replace(t, "[pad verborgen]")
        // Als laatste: lange aaneengesloten tekens die op sleutels lijken, maar geen klassenpaden (die bevatten punten).
        t = longSecret.replace(t) { m -> if (m.value.contains('.')) m.value else "[verborgen]" }
        return t
    }
}

enum class CrashCategory(val label: String) {
    MEMORY("Geheugen"),
    DATABASE("Opslag (database)"),
    STORAGE("Bestanden of netwerk"),
    UI("Weergave"),
    LOGIC("Programmafout"),
    TEST("Testfout (opzettelijk)"),
    UNKNOWN("Onbekend"),
}

class DeliberateTestCrash(message: String = "Gecontroleerde testfout") : RuntimeException(message)

data class CrashReport(
    val timeMillis: Long,
    val category: CrashCategory,
    val appVersion: String,
    val buildType: String,
    val apiLevel: Int,
    val exceptionType: String,
    val stackTrace: String,
) {
    fun toText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            appendLine("GameGraph diagnostiekrapport")
            appendLine("Tijdstip: ${fmt.format(Date(timeMillis))}")
            appendLine("Categorie: ${category.label}")
            appendLine("App: $appVersion ($buildType)")
            appendLine("Android API: $apiLevel")
            appendLine("Fouttype: $exceptionType")
            appendLine()
            appendLine("Stacktrace (gevoelige gegevens verwijderd):")
            append(stackTrace)
        }
    }

    companion object {
        const val MAX_LINES = 80

        fun categorize(t: Throwable): CrashCategory {
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < 8) {
                val name = cur.javaClass.name
                when {
                    cur is DeliberateTestCrash -> return CrashCategory.TEST
                    cur is OutOfMemoryError -> return CrashCategory.MEMORY
                    name.startsWith("android.database") || name.startsWith("androidx.room") -> return CrashCategory.DATABASE
                    cur is IOException -> return CrashCategory.STORAGE
                }
                cur = cur.cause
                depth++
            }
            return if (t.stackTrace.any { it.className.startsWith("androidx.compose") }) CrashCategory.UI
            else if (t is RuntimeException || t is Error) CrashCategory.LOGIC else CrashCategory.UNKNOWN
        }

        fun from(t: Throwable, now: Long): CrashReport {
            val lines = LogSanitizer.sanitize(t.stackTraceToString()).lines()
            val trimmed = if (lines.size > MAX_LINES) lines.take(MAX_LINES) + "… (ingekort)" else lines
            return CrashReport(
                timeMillis = now,
                category = categorize(t),
                appVersion = BuildConfig.VERSION_NAME,
                buildType = BuildConfig.DISTRIBUTION,
                apiLevel = Build.VERSION.SDK_INT,
                exceptionType = t.javaClass.name,
                stackTrace = trimmed.joinToString("\n"),
            )
        }
    }
}

/** Bewaart crashrapporten lokaal (maximaal [KEEP]); nooit naar een server. */
class CrashStore(private val dir: File) {
    fun save(report: CrashReport) {
        dir.mkdirs()
        File(dir, "crash-${report.timeMillis}.txt").writeText(report.toText())
        dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    fun latest(): String? = dir.listFiles { f -> f.name.startsWith("crash-") }
        ?.maxByOrNull { it.name }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        const val KEEP = 5
        fun forContext(context: Context) = CrashStore(File(context.filesDir, "crash"))
    }
}

/** Eenvoudige sleutel-waardeopslag, zodat [RecoveryState] zonder Android te testen is. */
interface KeyValues {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedPrefsKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun getString(key: String): String? = prefs.getString(key, null)
    // commit() i.p.v. apply(): we schrijven vlak voordat het proces stopt.
    override fun putString(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit() }
}

/**
 * Houdt bij of de vorige start in een crash eindigde en voorkomt eindeloos opnieuw proberen:
 * bij [MAX_CRASHES] crashes binnen [WINDOW_MILLIS] is "Opnieuw proberen" geblokkeerd.
 */
class RecoveryState(private val kv: KeyValues) {
    fun recordCrash(now: Long) {
        val recent = crashTimes().filter { now - it < WINDOW_MILLIS * 5 }.takeLast(MAX_HISTORY - 1) + now
        kv.putString(KEY_TIMES, recent.joinToString(","))
        kv.putString(KEY_NOTICE, "1")
    }

    fun crashTimes(): List<Long> = kv.getString(KEY_TIMES)?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    fun isCrashLoop(now: Long): Boolean = crashTimes().count { now - it in 0..WINDOW_MILLIS } >= MAX_CRASHES

    /** Eenmalig true na een crash, zodat de app kan melden dat ze is hersteld. */
    fun consumeRecoveryNotice(): Boolean {
        val had = kv.getString(KEY_NOTICE) != null
        if (had) kv.putString(KEY_NOTICE, null)
        return had
    }

    /** De app draait stabiel: begin met een schone lei. */
    fun markStable() { kv.putString(KEY_TIMES, null) }

    var lastSafeRoute: String?
        get() = kv.getString(KEY_ROUTE)
        set(value) { kv.putString(KEY_ROUTE, value) }

    fun resetAll() {
        kv.putString(KEY_TIMES, null)
        kv.putString(KEY_NOTICE, null)
        kv.putString(KEY_ROUTE, null)
    }

    companion object {
        const val MAX_CRASHES = 3
        const val WINDOW_MILLIS = 120_000L
        private const val MAX_HISTORY = 8
        private const val KEY_TIMES = "crash_times"
        private const val KEY_NOTICE = "recovery_notice"
        private const val KEY_ROUTE = "last_safe_route"

        fun forContext(context: Context) =
            RecoveryState(SharedPrefsKeyValues(context.getSharedPreferences("recovery", Context.MODE_PRIVATE)))
    }
}

/**
 * Globale uncaught-exception-handler. Hij schrijft een lokaal rapport, start het aparte herstelscherm
 * (eigen proces) en beëindigt daarna het huidige proces. Er wordt bewust niet doorgedraaid in een onzekere staat.
 */
class CrashHandler(
    private val app: Application,
    private val store: CrashStore,
    private val recovery: RecoveryState,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {
    @Volatile private var handling = false

    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (handling) { finish(); return }
        handling = true
        try {
            val now = clock()
            runCatching { store.save(CrashReport.from(error, now)) }
            runCatching { recovery.recordCrash(now) }
            val intent = Intent(app, CrashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(intent)
        } catch (_: Throwable) {
            // Mislukt het herstelscherm, dan laten we het systeem de crash afhandelen.
            runCatching { previous?.uncaughtException(thread, error) }
        } finally {
            finish()
        }
    }

    private fun finish() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    companion object {
        fun install(app: Application) {
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(app, CrashStore.forContext(app), RecoveryState.forContext(app), Thread.getDefaultUncaughtExceptionHandler())
            )
        }

        /** Welk proces draait er? Het herstelscherm draait in `:recovery` en mag de database niet openen. */
        fun isRecoveryProcess(): Boolean = try {
            File("/proc/self/cmdline").readText().trim('\u0000', ' ').endsWith(":recovery")
        } catch (e: Exception) {
            false
        }
    }
}
