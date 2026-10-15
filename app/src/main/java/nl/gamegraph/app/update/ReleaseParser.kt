package nl.gamegraph.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Semantische versie (major.minor.patch, optioneel -suffix). Een prerelease-suffix is lager dan dezelfde versie zonder. */
data class Version(val major: Int, val minor: Int, val patch: Int, val suffix: String? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun toString() = "$major.$minor.$patch" + (suffix?.let { "-$it" } ?: "")

    companion object {
        private val pattern = Regex("""^v?(\d{1,6})\.(\d{1,6})(?:\.(\d{1,6}))?(?:[-+]([0-9A-Za-z.\-]+))?$""")

        /** null bij een ongeldige versie; dat is een verwacht geval (dan wordt de release genegeerd). */
        fun parse(text: String?): Version? {
            val m = pattern.matchEntire(text?.trim() ?: return null) ?: return null
            return Version(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(), m.groupValues[4].ifEmpty { null },
            )
        }
    }
}

@Serializable
data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    @SerialName("content_type") val contentType: String? = null,
    /** GitHub vermeldt bij nieuwere assets zelf een digest, bijvoorbeeld "sha256:abc...". */
    val digest: String? = null,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

/** Optioneel bestand `gamegraph-release.json` bij een release. Zie RELEASING.md. */
@Serializable
data class ReleaseManifest(
    val versionCode: Int? = null,
    val versionName: String? = null,
    val apk: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    @SerialName("package") val packageName: String? = null,
    val minSdk: Int? = null,
)

data class InstalledApp(val packageName: String, val versionCode: Int, val versionName: String, val sdkInt: Int)

data class UpdateInfo(
    val tag: String,
    val version: Version,
    val versionCode: Int?,
    val notes: String,
    val prerelease: Boolean,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val pageUrl: String?,
)

enum class UpdateFailure { NO_RELEASES, NO_APK, AMBIGUOUS_APK, UNTRUSTED_URL, INCOMPATIBLE, INVALID_DATA, NETWORK, RATE_LIMITED, NOT_FOUND, SERVER }

sealed interface UpdateCheckResult {
    data class UpToDate(val latestTag: String?) : UpdateCheckResult
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class Failed(val reason: UpdateFailure, val detail: String? = null) : UpdateCheckResult
}

/** Levert de tekst van een bijlage (manifest of checksum-bestand); null bij een fout. */
fun interface AssetTextProvider {
    fun fetch(asset: GhAsset): String?
}

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val MANIFEST_NAME = "gamegraph-release.json"
    private val bodyCode = Regex("""(?im)^\s*versionCode\s*[:=]\s*(\d{1,9})\s*$""")
    private val hex64 = Regex("""(?i)\b[0-9a-f]{64}\b""")

    fun parseReleases(text: String): List<GhRelease>? = try {
        json.decodeFromString<List<GhRelease>>(text)
    } catch (e: Exception) {
        null
    }

    fun parseManifest(text: String?): ReleaseManifest? = try {
        text?.let { json.decodeFromString<ReleaseManifest>(it) }
    } catch (e: Exception) {
        null
    }

    fun normalizeSha(raw: String?): String? {
        val value = raw?.trim()?.removePrefix("sha256:")?.trim() ?: return null
        return hex64.find(value)?.value?.lowercase()
    }

    /** Alleen https-downloads van GitHub, en alleen uit de eigen repository. */
    fun isTrustedDownloadUrl(url: String, owner: String, repo: String): Boolean {
        val m = Regex("""^https://([a-z0-9.\-]+)(/[^?#]*)""", RegexOption.IGNORE_CASE).find(url) ?: return false
        val host = m.groupValues[1].lowercase()
        val path = m.groupValues[2]
        return host == "github.com" && path.startsWith("/$owner/$repo/releases/download/", ignoreCase = true)
    }

    /**
     * Kiest de beste release en bepaalt of die nieuwer is dan de geïnstalleerde app.
     * Vergelijking: eerst versionCode (uit manifest of release-notes), anders de versietag tegen versionName.
     */
    fun resolve(
        releases: List<GhRelease>,
        installed: InstalledApp,
        owner: String,
        repo: String,
        allowPrerelease: Boolean,
        texts: AssetTextProvider = AssetTextProvider { null },
    ): UpdateCheckResult {
        val usable = releases
            .filter { !it.draft && (allowPrerelease || !it.prerelease) }
            .mapNotNull { r -> Version.parse(r.tag)?.let { r to it } }
        if (usable.isEmpty()) return UpdateCheckResult.Failed(UpdateFailure.NO_RELEASES)

        val installedVersion = Version.parse(installed.versionName.substringBefore("-debug"))
        // Probeer releases van nieuw naar oud tot er één bruikbaar is (bijv. een release zonder APK overslaan).
        var firstProblem: UpdateCheckResult.Failed? = null
        for ((release, version) in usable.sortedByDescending { it.second }) {
            val manifestAsset = release.assets.firstOrNull { it.name.equals(MANIFEST_NAME, ignoreCase = true) }
            val manifest = manifestAsset?.let { parseManifest(texts.fetch(it)) }
            val apk = pickApk(release, manifest)
            if (apk is ApkChoice.None || apk is ApkChoice.Ambiguous) {
                if (firstProblem == null) {
                    firstProblem = UpdateCheckResult.Failed(
                        if (apk is ApkChoice.Ambiguous) UpdateFailure.AMBIGUOUS_APK else UpdateFailure.NO_APK, release.tag
                    )
                }
                continue
            }
            apk as ApkChoice.One
            val remoteCode = manifest?.versionCode ?: bodyCode.find(release.body.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val newer = when {
                remoteCode != null -> remoteCode > installed.versionCode
                installedVersion != null -> version > installedVersion
                else -> false
            }
            if (!newer) return UpdateCheckResult.UpToDate(release.tag)

            if (manifest?.packageName != null && manifest.packageName != installed.packageName)
                return UpdateCheckResult.Failed(UpdateFailure.INCOMPATIBLE, "andere app: ${manifest.packageName}")
            if (manifest?.minSdk != null && manifest.minSdk > installed.sdkInt)
                return UpdateCheckResult.Failed(UpdateFailure.INCOMPATIBLE, "vereist Android API ${manifest.minSdk}")
            if (!isTrustedDownloadUrl(apk.asset.downloadUrl, owner, repo))
                return UpdateCheckResult.Failed(UpdateFailure.UNTRUSTED_URL, apk.asset.name)

            val sha = normalizeSha(manifest?.sha256) ?: normalizeSha(apk.asset.digest) ?: checksumFromSiblings(release, apk.asset, texts)
            return UpdateCheckResult.Available(
                UpdateInfo(
                    tag = release.tag, version = version, versionCode = remoteCode,
                    notes = release.body.orEmpty().trim(), prerelease = release.prerelease,
                    apkName = apk.asset.name, apkUrl = apk.asset.downloadUrl,
                    apkSize = apk.asset.size.takeIf { it > 0 } ?: manifest?.size ?: 0L,
                    sha256 = sha, pageUrl = release.htmlUrl,
                )
            )
        }
        return firstProblem ?: UpdateCheckResult.Failed(UpdateFailure.NO_APK)
    }

    private sealed interface ApkChoice {
        data object None : ApkChoice
        data object Ambiguous : ApkChoice
        data class One(val asset: GhAsset) : ApkChoice
    }

    private fun pickApk(release: GhRelease, manifest: ReleaseManifest?): ApkChoice {
        manifest?.apk?.let { wanted ->
            release.assets.firstOrNull { it.name == wanted }?.let { return ApkChoice.One(it) }
        }
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true) }
        return when (apks.size) {
            0 -> ApkChoice.None
            1 -> ApkChoice.One(apks[0])
            else -> apks.singleOrNull { it.name.startsWith("GameGraph", ignoreCase = true) }?.let { ApkChoice.One(it) } ?: ApkChoice.Ambiguous
        }
    }

    private fun checksumFromSiblings(release: GhRelease, apk: GhAsset, texts: AssetTextProvider): String? {
        val sibling = release.assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.equals("SHA256SUMS", ignoreCase = true) || it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
            ?: return null
        val text = texts.fetch(sibling) ?: return null
        // Bij een SHA256SUMS-bestand: zoek de regel van de APK; bij een los .sha256-bestand: de eerste hash.
        val line = text.lines().firstOrNull { it.contains(apk.name) } ?: text.lines().firstOrNull { hex64.containsMatchIn(it) }
        return normalizeSha(line)
    }
}

/** Controle van een gedownload bestand: grootte en SHA-256. Dit vervangt NIET de handtekeningcontrole van Android. */
object ApkVerifier {
    sealed interface Outcome {
        data object Ok : Outcome
        data class WrongSize(val expected: Long, val actual: Long) : Outcome
        data class WrongChecksum(val expected: String, val actual: String) : Outcome
        data object Missing : Outcome
    }

    fun sha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedSize: Long, expectedSha256: String?): Outcome {
        if (!file.exists() || file.length() == 0L) return Outcome.Missing
        if (expectedSize > 0 && file.length() != expectedSize) return Outcome.WrongSize(expectedSize, file.length())
        val expected = ReleaseParser.normalizeSha(expectedSha256) ?: return Outcome.Ok
        val actual = file.inputStream().use { sha256(it) }
        return if (actual == expected) Outcome.Ok else Outcome.WrongChecksum(expected, actual)
    }
}
