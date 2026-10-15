import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun loadProps(path: String) = Properties().apply {
    val f = rootProject.file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

val versionProps = loadProps("version.properties")

// Ondertekening: eerst signing/keystore.properties (lokaal, staat in .gitignore),
// anders omgevingsvariabelen (GitHub Actions). Nooit sleutels of wachtwoorden in Git.
val signingProps = loadProps("signing/keystore.properties")
fun signingValue(key: String, env: String): String? =
    (signingProps[key] as String?) ?: System.getenv(env)

val keystoreFile: File? = signingValue("storeFile", "GAMEGRAPH_KEYSTORE_FILE")?.let {
    val f = File(it)
    if (f.isAbsolute) f else rootProject.file("signing/$it")
}?.takeIf { it.exists() }
val keystorePassword = signingValue("storePassword", "GAMEGRAPH_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "GAMEGRAPH_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "GAMEGRAPH_KEY_PASSWORD")
val hasSigningKey = keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null

// De GitHub-repository waaruit de updater releases haalt. Overschrijfbaar met -PgameGraphRepoOwner / -PgameGraphRepoName.
val repoOwner = (project.findProperty("gameGraphRepoOwner") as String?) ?: "BossevdK"
val repoName = (project.findProperty("gameGraphRepoName") as String?) ?: "gamegraph"

android {
    namespace = "nl.gamegraph.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.gamegraph.app"
        minSdk = 26
        targetSdk = 36
        versionCode = (versionProps["versionCode"] as String).trim().toInt()
        versionName = (versionProps["versionName"] as String).trim()
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"$repoOwner\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"$repoName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DISTRIBUTION", "\"debug\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DISTRIBUTION", "\"release\"")
            if (hasSigningKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Een releasebuild zonder ondertekeningssleutel faalt direct en duidelijk,
// in plaats van een APK op te leveren die niet te installeren is.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }
    if (wantsRelease && !hasSigningKey) {
        throw GradleException(
            "Release-ondertekening ontbreekt. Maak signing/keystore.properties (storeFile, storePassword, keyAlias, keyPassword) " +
                "of zet GAMEGRAPH_KEYSTORE_FILE, GAMEGRAPH_KEYSTORE_PASSWORD, GAMEGRAPH_KEY_ALIAS en GAMEGRAPH_KEY_PASSWORD. Zie RELEASING.md."
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
