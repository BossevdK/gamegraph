package nl.gamegraph.app

import nl.gamegraph.app.update.ApkVerifier
import nl.gamegraph.app.update.AssetTextProvider
import nl.gamegraph.app.update.GhAsset
import nl.gamegraph.app.update.GhRelease
import nl.gamegraph.app.update.InstalledApp
import nl.gamegraph.app.update.ReleaseParser
import nl.gamegraph.app.update.UpdateCheckResult
import nl.gamegraph.app.update.UpdateFailure
import nl.gamegraph.app.update.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdaterTest {
    private val owner = "BossevdK"
    private val repo = "gamegraph"
    private val installed = InstalledApp("nl.gamegraph.app", versionCode = 10, versionName = "1.0.0", sdkInt = 34)

    @get:Rule val tmp = TemporaryFolder()

    private fun url(tag: String, name: String) = "https://github.com/$owner/$repo/releases/download/$tag/$name"

    private fun asset(tag: String, name: String, size: Long = 1000, digest: String? = null) =
        GhAsset(name = name, size = size, downloadUrl = url(tag, name), digest = digest)

    private fun release(tag: String, assets: List<GhAsset> = listOf(asset(tag, "GameGraph.apk")), pre: Boolean = false, body: String? = null, draft: Boolean = false) =
        GhRelease(tag = tag, body = body, prerelease = pre, draft = draft, assets = assets)

    private fun resolve(releases: List<GhRelease>, allowPre: Boolean = false, texts: AssetTextProvider = AssetTextProvider { null }, app: InstalledApp = installed) =
        ReleaseParser.resolve(releases, app, owner, repo, allowPre, texts)

    // ---- Versies ----

    @Test fun versionsParseAndCompare() {
        assertEquals(Version(1, 2, 0), Version.parse("v1.2.0"))
        assertEquals(Version(1, 2, 0), Version.parse("1.2"))
        assertTrue(Version.parse("v1.10.0")!! > Version.parse("v1.9.9")!!)
        assertTrue(Version.parse("v2.0.0")!! > Version.parse("v1.99.99")!!)
        assertTrue("prerelease is lager", Version.parse("v1.0.0-beta1")!! < Version.parse("v1.0.0")!!)
        assertTrue(Version.parse("v1.0.0-beta2")!! > Version.parse("v1.0.0-beta1")!!)
    }

    @Test fun invalidVersionsAreRejected() {
        listOf("", "latest", "v", "vX.Y.Z", "1.2.3.4", "1..2", "v-1.0.0", "nightly-2024").forEach { assertNull("'$it'", Version.parse(it)) }
        assertNull(Version.parse(null))
    }

    // ---- Selecteren ----

    @Test fun newerVersionNameIsOffered() {
        val r = resolve(listOf(release("v1.1.0"), release("v1.0.0")))
        r as UpdateCheckResult.Available
        assertEquals("v1.1.0", r.info.tag)
        assertEquals("GameGraph.apk", r.info.apkName)
    }

    @Test fun sameOrOlderIsUpToDate() {
        assertTrue(resolve(listOf(release("v1.0.0"))) is UpdateCheckResult.UpToDate)
        assertTrue(resolve(listOf(release("v0.9.0"))) is UpdateCheckResult.UpToDate)
    }

    @Test fun versionCodeFromManifestDecides() {
        val manifest = """{"versionCode": 11, "versionName": "1.0.0", "apk": "GameGraph.apk", "sha256": "${"a".repeat(64)}"}"""
        val assets = listOf(asset("v1.0.0", "GameGraph.apk"), asset("v1.0.0", ReleaseParser.MANIFEST_NAME, 100))
        val texts = AssetTextProvider { if (it.name == ReleaseParser.MANIFEST_NAME) manifest else null }
        // Zelfde versienaam, maar hogere versionCode: toch een update.
        val r = resolve(listOf(release("v1.0.0", assets)), texts = texts) as UpdateCheckResult.Available
        assertEquals(11, r.info.versionCode)
        assertEquals("a".repeat(64), r.info.sha256)
        // Lagere of gelijke versionCode: geen update, ook al is de tag hoger.
        val same = """{"versionCode": 10}"""
        val r2 = resolve(listOf(release("v2.0.0", assets)), texts = { same })
        assertTrue(r2 is UpdateCheckResult.UpToDate)
    }

    @Test fun versionCodeFromReleaseNotes() {
        val r = resolve(listOf(release("v1.0.1", body = "Fixes\nversionCode: 12\n"))) as UpdateCheckResult.Available
        assertEquals(12, r.info.versionCode)
        val stale = resolve(listOf(release("v1.5.0", body = "versionCode: 10")))
        assertTrue(stale is UpdateCheckResult.UpToDate)
    }

    @Test fun prereleasesAndDraftsAreIgnoredByDefault() {
        val list = listOf(release("v2.0.0-beta1", pre = true), release("v1.5.0", draft = true), release("v1.0.0"))
        assertTrue(resolve(list) is UpdateCheckResult.UpToDate)
        val withPre = resolve(list, allowPre = true) as UpdateCheckResult.Available
        assertEquals("v2.0.0-beta1", withPre.info.tag)
        assertTrue(withPre.info.prerelease)
    }

    @Test fun invalidTagsAreSkipped() {
        val list = listOf(release("nightly"), release("latest"), release("v1.2.0"))
        val r = resolve(list) as UpdateCheckResult.Available
        assertEquals("v1.2.0", r.info.tag)
        assertEquals(UpdateFailure.NO_RELEASES, (resolve(listOf(release("nightly"))) as UpdateCheckResult.Failed).reason)
        assertEquals(UpdateFailure.NO_RELEASES, (resolve(emptyList()) as UpdateCheckResult.Failed).reason)
    }

    // ---- Ontbrekende of verdachte APK's ----

    @Test fun missingApkAssetIsReported() {
        val onlyNotes = release("v1.1.0", assets = listOf(asset("v1.1.0", "notes.txt")))
        val r = resolve(listOf(onlyNotes)) as UpdateCheckResult.Failed
        assertEquals(UpdateFailure.NO_APK, r.reason)
        // Een oudere release met APK wordt dan niet als "nieuwer" aangeboden.
        val fallback = resolve(listOf(onlyNotes, release("v1.0.0")))
        assertTrue(fallback is UpdateCheckResult.UpToDate)
    }

    @Test fun debugApksAreNotOffered() {
        val r = resolve(listOf(release("v1.1.0", assets = listOf(asset("v1.1.0", "GameGraph-debug.apk"))))) as UpdateCheckResult.Failed
        assertEquals(UpdateFailure.NO_APK, r.reason)
    }

    @Test fun ambiguousApksAreRefused() {
        val a = listOf(asset("v1.1.0", "one.apk"), asset("v1.1.0", "two.apk"))
        assertEquals(UpdateFailure.AMBIGUOUS_APK, (resolve(listOf(release("v1.1.0", a))) as UpdateCheckResult.Failed).reason)
        val preferred = listOf(asset("v1.1.0", "other.apk"), asset("v1.1.0", "GameGraph-1.1.0.apk"))
        assertEquals("GameGraph-1.1.0.apk", (resolve(listOf(release("v1.1.0", preferred))) as UpdateCheckResult.Available).info.apkName)
    }

    @Test fun downloadsFromOtherLocationsAreRefused() {
        assertTrue(ReleaseParser.isTrustedDownloadUrl(url("v1", "a.apk"), owner, repo))
        assertEquals(false, ReleaseParser.isTrustedDownloadUrl("http://github.com/$owner/$repo/releases/download/v1/a.apk", owner, repo))
        assertEquals(false, ReleaseParser.isTrustedDownloadUrl("https://evil.example/$owner/$repo/releases/download/v1/a.apk", owner, repo))
        assertEquals(false, ReleaseParser.isTrustedDownloadUrl("https://github.com/someone/else/releases/download/v1/a.apk", owner, repo))
        assertEquals(false, ReleaseParser.isTrustedDownloadUrl("https://github.com.evil.example/$owner/$repo/releases/download/v1/a.apk", owner, repo))
        val bad = GhAsset("GameGraph.apk", 10, "https://evil.example/GameGraph.apk")
        val r = resolve(listOf(release("v1.1.0", listOf(bad)))) as UpdateCheckResult.Failed
        assertEquals(UpdateFailure.UNTRUSTED_URL, r.reason)
    }

    @Test fun incompatibleManifestsAreRefused() {
        val assets = listOf(asset("v1.1.0", "GameGraph.apk"), asset("v1.1.0", ReleaseParser.MANIFEST_NAME, 50))
        val otherApp = resolve(listOf(release("v1.1.0", assets)), texts = { """{"versionCode": 11, "package": "com.other"}""" })
        assertEquals(UpdateFailure.INCOMPATIBLE, (otherApp as UpdateCheckResult.Failed).reason)
        val newerSdk = resolve(listOf(release("v1.1.0", assets)), texts = { """{"versionCode": 11, "minSdk": 99}""" })
        assertEquals(UpdateFailure.INCOMPATIBLE, (newerSdk as UpdateCheckResult.Failed).reason)
    }

    @Test fun brokenManifestFallsBackToVersionTag() {
        val assets = listOf(asset("v1.1.0", "GameGraph.apk"), asset("v1.1.0", ReleaseParser.MANIFEST_NAME, 50))
        val r = resolve(listOf(release("v1.1.0", assets)), texts = { "{ not json" })
        assertTrue(r is UpdateCheckResult.Available)
    }

    // ---- Parsen van GitHub-data ----

    @Test fun parsesGithubJsonAndIgnoresUnknownFields() {
        val json = """[{"tag_name":"v1.2.0","name":"x","draft":false,"prerelease":false,"unknown":1,
            "assets":[{"name":"GameGraph.apk","size":2048,"browser_download_url":"${url("v1.2.0", "GameGraph.apk")}","digest":"sha256:${"b".repeat(64)}","extra":true}]}]"""
        val releases = ReleaseParser.parseReleases(json)!!
        val r = resolve(releases) as UpdateCheckResult.Available
        assertEquals(2048, r.info.apkSize)
        assertEquals("b".repeat(64), r.info.sha256)
    }

    @Test fun invalidJsonYieldsNull() {
        assertNull(ReleaseParser.parseReleases("<html>rate limited</html>"))
        assertNull(ReleaseParser.parseReleases("{\"message\":\"Not Found\"}"))
        assertNull(ReleaseParser.parseManifest("nope"))
    }

    @Test fun checksumFromSiblingFile() {
        val assets = listOf(asset("v1.1.0", "GameGraph.apk"), asset("v1.1.0", "GameGraph.apk.sha256", 80))
        val texts = AssetTextProvider { "${"C".repeat(64)}  GameGraph.apk\n" }
        val r = resolve(listOf(release("v1.1.0", assets)), texts = texts) as UpdateCheckResult.Available
        assertEquals("c".repeat(64), r.info.sha256)
        val none = resolve(listOf(release("v1.1.0"))) as UpdateCheckResult.Available
        assertNull(none.info.sha256)
    }

    // ---- Downloadcontrole ----

    private fun fileWith(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun correctChecksumAndSizePass() {
        val data = ByteArray(5000) { it.toByte() }
        val f = fileWith(data)
        assertEquals(ApkVerifier.Outcome.Ok, ApkVerifier.verify(f, 5000, sha(data)))
        assertEquals(ApkVerifier.Outcome.Ok, ApkVerifier.verify(f, 5000, "sha256:" + sha(data).uppercase()))
        assertEquals("zonder checksum blijft de groottecontrole", ApkVerifier.Outcome.Ok, ApkVerifier.verify(f, 5000, null))
    }

    @Test fun wrongChecksumIsRejected() {
        val data = ByteArray(5000) { it.toByte() }
        val r = ApkVerifier.verify(fileWith(data), 5000, "0".repeat(64))
        assertTrue(r is ApkVerifier.Outcome.WrongChecksum)
    }

    @Test fun wrongSizeAndMissingFileAreRejected() {
        val data = ByteArray(100)
        assertTrue(ApkVerifier.verify(fileWith(data), 5000, null) is ApkVerifier.Outcome.WrongSize)
        assertEquals(ApkVerifier.Outcome.Missing, ApkVerifier.verify(java.io.File(tmp.root, "nope.apk"), 0, null))
        assertEquals(ApkVerifier.Outcome.Missing, ApkVerifier.verify(fileWith(ByteArray(0)), 0, null))
    }
}
