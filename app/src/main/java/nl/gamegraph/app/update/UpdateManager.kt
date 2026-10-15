package nl.gamegraph.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.app.PendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.gamegraph.app.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

data class HttpResult(val code: Int, val body: String?, val headers: Map<String, String> = emptyMap())

/** Dunne laag rond het netwerk, zodat de updater zonder internet getest kan worden. */
interface HttpFetcher {
    @Throws(IOException::class)
    fun get(url: String): HttpResult
}

class UrlConnectionFetcher : HttpFetcher {
    override fun get(url: String): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "GameGraph-Updater/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            return HttpResult(code, body, headers)
        } finally {
            c.disconnect()
        }
    }
}

/** Kleine opslag (SharedPreferences) voor de laatste updatecontrole; werkt ook in het herstelproces. */
class UpdateStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("updates", Context.MODE_PRIVATE))

    var lastCheckAt: Long
        get() = prefs.getLong("last_check_at", 0L)
        set(v) { prefs.edit().putLong("last_check_at", v).apply() }
    var lastResult: String
        get() = prefs.getString("last_result", "").orEmpty()
        set(v) { prefs.edit().putString("last_result", v).apply() }
    var pendingVersionCode: Int
        get() = prefs.getInt("pending_version_code", 0)
        set(v) { prefs.edit().putInt("pending_version_code", v).apply() }
    var pendingVersionName: String
        get() = prefs.getString("pending_version_name", "").orEmpty()
        set(v) { prefs.edit().putString("pending_version_name", v).apply() }

    fun clearPending() { prefs.edit().remove("pending_version_code").remove("pending_version_name").apply() }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val bytes: Long, val total: Long) : DownloadState
    data class Done(val file: File) : DownloadState
    data class Error(val message: String) : DownloadState
}

sealed interface InstallPrecheck {
    data object Ok : InstallPrecheck
    data class Blocked(val message: String) : InstallPrecheck
}

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data object Cancelled : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

/** Resultaten van de Android-installatie, zodat de UI ze kan tonen. */
object InstallEvents {
    val outcome = MutableStateFlow<InstallOutcome?>(null)
}

class UpdateManager(
    private val context: Context,
    private val fetcher: HttpFetcher = UrlConnectionFetcher(),
    private val store: UpdateStore = UpdateStore(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val owner = BuildConfig.UPDATE_REPO_OWNER
    private val repo = BuildConfig.UPDATE_REPO_NAME

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    val installed: InstalledApp
        get() = InstalledApp(context.packageName, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)

    val lastCheckAt: Long get() = store.lastCheckAt
    val lastResult: String get() = store.lastResult

    fun releasesUrl() = "https://api.github.com/repos/$owner/$repo/releases?per_page=15"

    /** Controleert GitHub Releases. Gooit nooit: alle fouten komen terug als [UpdateCheckResult.Failed]. */
    suspend fun check(includePrereleases: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        val result = try {
            val response = fetcher.get(releasesUrl())
            when {
                response.code == 404 -> UpdateCheckResult.Failed(UpdateFailure.NOT_FOUND)
                response.code == 429 || (response.code == 403 && response.headers["x-ratelimit-remaining"] == "0") ->
                    UpdateCheckResult.Failed(UpdateFailure.RATE_LIMITED)
                response.code !in 200..299 -> UpdateCheckResult.Failed(UpdateFailure.SERVER, "HTTP ${response.code}")
                else -> {
                    val releases = ReleaseParser.parseReleases(response.body.orEmpty())
                    if (releases == null) UpdateCheckResult.Failed(UpdateFailure.INVALID_DATA)
                    else ReleaseParser.resolve(releases, installed, owner, repo, includePrereleases) { asset -> fetchText(asset) }
                }
            }
        } catch (e: UnknownHostException) {
            UpdateCheckResult.Failed(UpdateFailure.NETWORK, "geen verbinding")
        } catch (e: SocketTimeoutException) {
            UpdateCheckResult.Failed(UpdateFailure.NETWORK, "time-out")
        } catch (e: IOException) {
            UpdateCheckResult.Failed(UpdateFailure.NETWORK)
        } catch (e: Exception) {
            UpdateCheckResult.Failed(UpdateFailure.INVALID_DATA)
        }
        store.lastCheckAt = clock()
        store.lastResult = describe(result)
        result
    }

    private fun fetchText(asset: GhAsset): String? {
        if (!ReleaseParser.isTrustedDownloadUrl(asset.downloadUrl, owner, repo) || asset.size > 256 * 1024) return null
        return runCatching { fetcher.get(asset.downloadUrl).takeIf { it.code in 200..299 }?.body }.getOrNull()
    }

    private fun updatesDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }

    fun clearDownloads() {
        runCatching { updatesDir().listFiles()?.forEach { it.delete() } }
        _download.value = DownloadState.Idle
    }

    /** Downloadt de APK naar app-eigen opslag en controleert grootte en checksum. */
    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (!ReleaseParser.isTrustedDownloadUrl(info.apkUrl, owner, repo)) {
            _download.value = DownloadState.Error("Onveilige downloadlink geweigerd.")
            return@withContext
        }
        val target = File(updatesDir(), "GameGraph-${info.version}.apk")
        val temp = File(updatesDir(), "${target.name}.part")
        try {
            temp.delete()
            _download.value = DownloadState.Running(0, info.apkSize)
            val c = URL(info.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "GameGraph-Updater/${BuildConfig.VERSION_NAME}")
            try {
                if (c.responseCode !in 200..299) {
                    _download.value = DownloadState.Error("Download mislukt (HTTP ${c.responseCode}).")
                    return@withContext
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                c.inputStream.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastEmit > 128 * 1024) { lastEmit = done; _download.value = DownloadState.Running(done, total) }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            when (val v = ApkVerifier.verify(temp, info.apkSize, info.sha256)) {
                ApkVerifier.Outcome.Ok -> {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Kon het bestand niet opslaan.")
                    _download.value = DownloadState.Done(target)
                }
                is ApkVerifier.Outcome.WrongChecksum -> {
                    temp.delete()
                    _download.value = DownloadState.Error("De controlesom klopt niet. Het bestand is verwijderd; probeer het opnieuw.")
                }
                is ApkVerifier.Outcome.WrongSize -> {
                    temp.delete()
                    _download.value = DownloadState.Error("Het gedownloade bestand heeft een andere grootte dan verwacht. Probeer het opnieuw.")
                }
                ApkVerifier.Outcome.Missing -> {
                    temp.delete()
                    _download.value = DownloadState.Error("Er is niets gedownload.")
                }
            }
        } catch (e: UnknownHostException) {
            temp.delete(); _download.value = DownloadState.Error("Geen internetverbinding.")
        } catch (e: IOException) {
            temp.delete(); _download.value = DownloadState.Error("Download onderbroken. Controleer je verbinding en probeer opnieuw.")
        } catch (e: Exception) {
            temp.delete(); _download.value = DownloadState.Error("Download mislukt.")
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Controleert vooraf of Android deze APK als update zal accepteren (zelfde package, hogere versie, zelfde handtekening). */
    @Suppress("DEPRECATION")
    fun precheck(apk: File): InstallPrecheck {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return InstallPrecheck.Blocked("Het gedownloade bestand is geen geldige app. Het is niet geïnstalleerd.")
        if (archive.packageName != context.packageName) {
            return InstallPrecheck.Blocked("Dit bestand hoort bij een andere app (${archive.packageName}) en wordt niet geïnstalleerd.")
        }
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (newCode <= BuildConfig.VERSION_CODE) {
            return InstallPrecheck.Blocked("Deze versie is niet nieuwer dan de geïnstalleerde versie.")
        }
        val current = pm.getPackageInfo(context.packageName, flags)
        if (signatureDigests(archive) != signatureDigests(current)) {
            return InstallPrecheck.Blocked(
                "Deze update is met een andere ondertekeningssleutel gemaakt dan de geïnstalleerde app, dus Android laat de installatie niet toe. " +
                    "Je huidige app en gegevens blijven ongewijzigd. Exporteer eventueel je gegevens in Instellingen voordat je de nieuwe versie handmatig naast of over de oude installeert."
            )
        }
        return InstallPrecheck.Ok
    }

    @Suppress("DEPRECATION")
    private fun signatureDigests(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Biedt de APK aan Android aan. De gebruiker bevestigt de installatie zelf; er is geen stille installatie. */
    fun install(apk: File, info: UpdateInfo?) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            info?.versionCode?.let { store.pendingVersionCode = it }
            info?.let { store.pendingVersionName = it.version.toString() }
            val intent = Intent(context, InstallStatusReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Roep dit bij het starten van de app aan: is de eerder aangeboden update inmiddels geïnstalleerd?
     * Geeft de nieuwe versienaam terug als dat zo is.
     */
    fun consumeInstalledUpdate(): String? {
        val pending = store.pendingVersionCode
        if (pending <= 0) return null
        return if (BuildConfig.VERSION_CODE >= pending) {
            val name = store.pendingVersionName.ifBlank { BuildConfig.VERSION_NAME }
            store.clearPending()
            clearDownloads()
            name
        } else null
    }

    companion object {
        fun describe(result: UpdateCheckResult): String = when (result) {
            is UpdateCheckResult.UpToDate -> "Up-to-date" + (result.latestTag?.let { " (nieuwste release $it)" } ?: "")
            is UpdateCheckResult.Available -> "Update beschikbaar: ${result.info.tag}"
            is UpdateCheckResult.Failed -> "Mislukt: " + failureText(result.reason)
        }

        fun failureText(reason: UpdateFailure): String = when (reason) {
            UpdateFailure.NO_RELEASES -> "Er zijn nog geen releases gepubliceerd."
            UpdateFailure.NO_APK -> "De nieuwste release bevat geen APK."
            UpdateFailure.AMBIGUOUS_APK -> "De release bevat meerdere APK's en het is niet duidelijk welke je nodig hebt."
            UpdateFailure.UNTRUSTED_URL -> "De downloadlink hoort niet bij de officiële repository en is geweigerd."
            UpdateFailure.INCOMPATIBLE -> "De nieuwste release is niet compatibel met dit toestel of deze app."
            UpdateFailure.INVALID_DATA -> "De releasegegevens zijn onleesbaar."
            UpdateFailure.NETWORK -> "Geen verbinding met GitHub. Controleer je internet en probeer opnieuw."
            UpdateFailure.RATE_LIMITED -> "GitHub staat te veel aanvragen niet toe. Probeer het over een uur opnieuw."
            UpdateFailure.NOT_FOUND -> "De releasepagina is niet gevonden. Controleer de repository-instelling."
            UpdateFailure.SERVER -> "GitHub gaf een fout terug. Probeer het later opnieuw."
        }
    }
}

/** Ontvangt de voortgang van de Android-pakketinstaller en opent zo nodig het bevestigingsscherm. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.outcome.value = InstallOutcome.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.outcome.value = InstallOutcome.Cancelled
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                InstallEvents.outcome.value = InstallOutcome.Failed(
                    "Android weigerde de update (conflict of andere handtekening). Je huidige app blijft ongewijzigd. $message".trim()
                )
            else -> InstallEvents.outcome.value = InstallOutcome.Failed("Installatie mislukt. De huidige app blijft bruikbaar. $message".trim())
        }
    }
}
