plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "io.github.sherazi5110.cloudstream"
    compileSdk = 36

    defaultConfig {
        // Installs next to the official app (com.lagradost.cloudstream3).
        applicationId = "io.github.sherazi5110.cloudstream"
        minSdk = 23
        targetSdk = 36
        // CI passes the release tag (-PappVersion=7.0.0); local builds use the numbers below.
        val tagVersion = (findProperty("appVersion") as String?)?.removePrefix("v")?.removePrefix("V")
        versionName = tagVersion ?: "9.0.1"
        versionCode = tagVersion?.let { v ->
            val (major, minor, patch) = (v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
            major * 10000 + minor * 100 + patch
        } ?: 90001
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Google Drive backup: the project's Google sign-in client (GitHub secrets, docs/google-drive-setup.md).
        // For an installed app Google doesn't treat the client secret as secret; it ships in the app.
        fun quoted(name: String) = "\"" + (System.getenv(name) ?: "").replace("\"", "") + "\""
        buildConfigField("String", "GOOGLE_CLIENT_ID", quoted("GOOGLE_CLIENT_ID"))
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", quoted("GOOGLE_CLIENT_SECRET"))
        // WeTrakr sync: the app key (client_id) of the Stream Goat app registered on wetrakr.com (GitHub secret
        // WETRAKR_CLIENT_ID). Its client secret is never put in the app: the device sign-in doesn't need it.
        buildConfigField("String", "WETRAKR_CLIENT_ID", quoted("WETRAKR_CLIENT_ID"))
    }

    // The release key comes from the environment (GitHub Actions secrets, see android/README.md) and is never
    // committed. Without it, release builds are signed with the debug key: fine for testing, but each machine's
    // debug key differs, so such builds can't update each other.
    val keystore = System.getenv("CS_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("CS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CS_KEY_ALIAS")
            keyPassword = System.getenv("CS_KEY_PASSWORD") ?: System.getenv("CS_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Extensions are loaded at runtime and call into the library, Jackson, OkHttp, jsoup, Rhino…
            // by name, so nothing may be renamed or removed.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        // libVLC is ~40-60 MB per CPU type: compress it in the APK (it is extracted once on install).
        jniLibs.useLegacyPackaging = true
    }

    // One APK per CPU type (smaller downloads), plus a universal one that runs everywhere.
    // Most phones are arm64-v8a; many Android TV boxes and Fire TV sticks are armeabi-v7a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // We are the host app, like upstream's :app module.
        optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease")
    }
}

dependencies {
    implementation(project(":cs-library"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Libraries the official app ships, which extensions are compiled against and expect to find at runtime
    // (same versions as upstream's version catalog). Without them, e.g. "Failed resolution of
    // Landroidx/appcompat/app/AppCompatActivity" when installing an extension.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("org.conscrypt:conscrypt-android") { version { strictly("2.5.2") } }
    implementation("app.cash.zipline:zipline-android:1.27.0")

    // Posters and backdrops (OkHttp, so the app's DNS/cookie settings apply; WebP is native on Android).
    // Liquid glass: real blur of the page behind bars and panels (Android 12+; a tinted fallback below).
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // The player: libVLC for Android (Maven Central), with this app's own controls.
    implementation("org.videolan.android:libvlc-all:3.6.5")
    // Torrent streaming (opt-in, Settings → Player): libtorrent through libtorrent4j, native code for each ABI.
    val libtorrent = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent")
    // Unit tests run on this computer (Linux): its native library, for a seeder-to-player test on localhost.
    testImplementation("org.libtorrent4j:libtorrent4j-linux:$libtorrent")
    // NewPipe Extractor, which the official app ships: extensions such as YoutubeProvider (official repository) use it.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    // QR codes for "Add from phone" (scan with the phone's camera).
    implementation("com.google.zxing:core:3.5.3")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
}

// Paparazzi (Android screenshots drawn on the computer: ./gradlew :app:recordPaparazziDebug) clashes with
// Gradle's HTML test report; the XML results are kept.
tasks.withType<Test>().configureEach { reports.html.required.set(false) }

// The torrent test runs libtorrent's native engine, which can crash the test process as it exits when it shares
// that process with Paparazzi's renderer: it gets a process of its own, right after the other unit tests.
afterEvaluate {
    val unit = tasks.named<Test>("testDebugUnitTest")
    unit.configure { filter { excludeTestsMatching("*TorrentStreamingTest"); isFailOnNoMatchingTests = false } }
    val torrent = tasks.register<Test>("torrentUnitTest") {
        val base = unit.get()
        testClassesDirs = base.testClassesDirs
        classpath = base.classpath
        jvmArgs(base.allJvmArgs.filter { it.startsWith("--add-opens") || it.startsWith("-D") })
        filter { includeTestsMatching("*TorrentStreamingTest"); isFailOnNoMatchingTests = false }
        dependsOn(base.dependsOn)
    }
    unit.configure { finalizedBy(torrent) }
}
