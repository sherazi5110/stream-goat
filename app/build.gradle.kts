plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // We are the host app, like upstream's :app module.
        optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease")
    }
}

dependencies {
    implementation(project(":android-shim"))
    // Translates Dalvik bytecode (classes.dex inside .cs3) to JVM bytecode.
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.11.0")
    // NewPipe Extractor, which the official app ships: extensions such as YoutubeProvider (official repository) use it.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
    // org.xmlpull.v1 is part of Android; extensions' settings screens reference it (42 of 587 scanned).
    implementation("xmlpull:xmlpull:1.1.3.1")
    // JavaFX 21 LTS (runs on the Java 21 Ubuntu ships); GPU-accelerated, CSS-styled UI.
    // JavaFX ships per-system native code: build on Windows for the Windows version.
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val fxPlatform = when {
        windows -> "win"
        System.getProperty("os.arch") == "aarch64" -> "linux-aarch64"
        else -> "linux"
    }
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:21.0.8:$fxPlatform")
    }
    // Many streaming sites serve WebP posters, which JavaFX cannot decode itself.
    implementation("com.github.usefulness:webp-imageio:0.11.0")
    // Built-in player: libVLC 3 (from the system's VLC packages) through JNA.
    implementation("uk.co.caprica:vlcj:4.12.1")
    testImplementation(kotlin("test"))
}

application {
    applicationName = "cloudstream-linux"
    mainClass.set("com.lagradost.cloudstream3.linux.MainKt")
    applicationDefaultJvmArgs = listOf(
        // Plugin bytecode comes from dex2jar and may reference Android classes we only stub.
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:-BytecodeVerificationRemote",
        "--enable-native-access=ALL-UNNAMED",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.test {
    useJUnitPlatform()
    // The tests load the example extension both as a real Dalvik .cs3 and as a cross-platform .jar.
    val repo = project(":examples:InternetArchiveProvider").layout.buildDirectory.dir("repo")
    val demo = project(":tests:DemoProvider").layout.buildDirectory.dir("repo")
    dependsOn(":examples:InternetArchiveProvider:makeRepo", ":tests:DemoProvider:makeRepo")
    systemProperty("example.repo", repo.get().asFile.absolutePath)
    systemProperty("demo.repo", demo.get().asFile.absolutePath)
    systemProperty("tests.dir", rootProject.file("tests").absolutePath)
    systemProperty("cloudstream.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // The mock sites run with Python; Windows calls it "python" (-Ptest.python=python).
    systemProperty("test.python", (findProperty("test.python") as String?) ?: "python3")
    // Windows: the VLC folder the app bundles (-PvlcDir=...), for the libVLC playback test.
    (findProperty("vlcDir") as String?)?.let { systemProperty("jna.library.path", it); systemProperty("cs.vlc.dir", it) }
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
}

// Self-contained .deb (bundles a Java runtime) built with the JDK's jpackage:
//   ./gradlew :app:packageDeb  ->  app/build/jpackage/cloudstream-linux_<version>_amd64.deb
val packageDeb by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(tasks.installDist)
    val lib = layout.buildDirectory.dir("install/cloudstream-linux/lib")
    val out = layout.buildDirectory.dir("jpackage")
    val resources = layout.buildDirectory.dir("jpackage-resources")
    val packaging = rootProject.file("packaging/linux")
    doFirst {
        out.get().asFile.deleteRecursively()
        val res = resources.get().asFile.apply { mkdirs() }
        // jpackage picks up <name>.desktop from --resource-dir, so the link handler is registered on install.
        res.resolve("cloudstream-linux.desktop").writeText(
            packaging.resolve("cloudstream-linux.desktop").readText()
                .replace("@EXEC@", "/opt/cloudstream-linux/bin/cloudstream-linux")
                .replace("@ICON@", "/opt/cloudstream-linux/lib/cloudstream-linux.png")
        )
    }
    commandLine(buildList {
        // -PjpackageType=app-image builds the unpacked app without dpkg/fakeroot.
        val type = (findProperty("jpackageType") as String?) ?: "deb"
        addAll(listOf("jpackage", "--type", type, "--name", "cloudstream-linux", "--app-version", "2.0.0"))
        addAll(listOf("--input", lib.get().asFile.path, "--main-jar", "app.jar"))
        addAll(listOf("--main-class", application.mainClass.get(), "--dest", out.get().asFile.path))
        addAll(listOf("--resource-dir", resources.get().asFile.path))
        addAll(listOf("--icon", packaging.resolve("cloudstream-linux.png").path))
        addAll(listOf("--description", "Stream Goat: movies, series, anime and Live TV from CloudStream extensions"))
        addAll(listOf("--jlink-options", "--strip-debug --no-man-pages --no-header-files --strip-native-commands"))
        // Android gives extensions roughly java.base; the rest is for Swing, TLS, Jackson, Rhino and okhttp.
        addAll(listOf("--add-modules", listOf(
            "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.net.http",
            "java.prefs", "java.scripting", "java.sql", "java.xml", "jdk.charsets", "jdk.crypto.ec",
            "jdk.localedata", "jdk.unsupported", "jdk.zipfs", "jdk.httpserver",
        ).joinToString(",")))
        if (type == "deb") {
            addAll(listOf("--linux-shortcut", "--linux-menu-group", "AudioVideo;Video", "--linux-app-category", "video"))
            addAll(listOf("--linux-deb-maintainer", "cloudstream-linux@users.noreply.github.com"))
            addAll(listOf("--linux-package-deps", "libvlc5, vlc-plugin-base"))
        }
        application.applicationDefaultJvmArgs.forEach { addAll(listOf("--java-options", it)) }
    })
}

// Windows: the app image (Stream Goat\Stream Goat.exe with its own Java runtime and VLC) and the .msi installer,
// built with the JDK's jpackage on Windows (CI: the "windows" job in .github/workflows/build.yml).
//   ./gradlew :app:packageWindows -PvlcDir=C:\path\to\vlc                 -> build\jpackage-win\Stream Goat\
//   ./gradlew :app:packageWindows -PvlcDir=... -PjpackageType=msi          -> build\jpackage-win\Stream Goat-<v>.msi
val packageWindows by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(tasks.installDist)
    val lib = layout.buildDirectory.dir("install/cloudstream-linux/lib")
    val input = layout.buildDirectory.dir("jpackage-win-input")
    val out = layout.buildDirectory.dir("jpackage-win")
    val version = file("src/main/kotlin/com/lagradost/cloudstream3/linux/ui/AppWindow.kt").readText()
        .let { Regex("VERSION = \"([0-9.]+)").find(it)!!.groupValues[1] }
    val type = (findProperty("jpackageType") as String?) ?: "app-image"
    doFirst {
        val inDir = input.get().asFile.apply { deleteRecursively(); mkdirs() }
        lib.get().asFile.copyRecursively(inDir)
        // VLC for Windows (libvlc.dll, libvlccore.dll, plugins\) goes into <app>\app\vlc; see Os.bundledVlcDir.
        (findProperty("vlcDir") as String?)?.let { file(it).copyRecursively(inDir.resolve("vlc")) }
        if (type == "app-image") out.get().asFile.deleteRecursively()
    }
    commandLine(buildList {
        addAll(listOf("jpackage", "--type", type, "--name", "Stream Goat", "--app-version", version))
        addAll(listOf("--vendor", "Syed Ali Hamza", "--dest", out.get().asFile.path))
        addAll(listOf("--description", "Stream Goat: movies, series, anime and Live TV from CloudStream extensions"))
        if (type == "app-image") {
            addAll(listOf("--input", input.get().asFile.path, "--main-jar", "app.jar", "--main-class", application.mainClass.get()))
            addAll(listOf("--icon", rootProject.file("packaging/windows/cloudstream.ico").path))
            // A second launcher with a console window for the command line (streamgoat-cli.exe doctor, install…).
            addAll(listOf("--add-launcher", "streamgoat-cli=" + rootProject.file("packaging/windows/cli-launcher.properties").path))
            addAll(listOf("--jlink-options", "--strip-debug --no-man-pages --no-header-files --strip-native-commands"))
            addAll(listOf("--add-modules", listOf(
                "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.net.http",
                "java.prefs", "java.scripting", "java.sql", "java.xml", "jdk.charsets", "jdk.crypto.ec",
                "jdk.localedata", "jdk.unsupported", "jdk.zipfs", "jdk.httpserver",
            ).joinToString(",")))
            application.applicationDefaultJvmArgs.forEach { addAll(listOf("--java-options", it)) }
        } else {
            // The installer wraps the app image built first: per-user (no admin prompt), Start menu and desktop
            // shortcuts, upgrades in place (the upgrade code never changes).
            addAll(listOf("--app-image", out.get().asFile.resolve("Stream Goat").path))
            addAll(listOf("--win-per-user-install", "--win-menu", "--win-menu-group", "Stream Goat", "--win-shortcut"))
            addAll(listOf("--win-upgrade-uuid", "6f1c2b9e-4d1a-4c2e-9a57-3e5b8d0c7a41"))
            addAll(listOf("--about-url", "https://github.com/sherazi5110/stream-goat"))
        }
    })
}

// Google Drive backup: the project's Google sign-in client, from the environment (GitHub secrets
// GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET, see docs/google-drive-setup.md), into a resource read by CloudConfig.
val cloudConfig = tasks.register("cloudConfig") {
    val out = layout.buildDirectory.dir("generated/cloud")
    val id = providers.environmentVariable("GOOGLE_CLIENT_ID").orElse("")
    val secret = providers.environmentVariable("GOOGLE_CLIENT_SECRET").orElse("")
    inputs.property("id", id)
    inputs.property("secret", secret)
    outputs.dir(out)
    doLast {
        out.get().file("stream-goat-cloud.properties").asFile.apply { parentFile.mkdirs() }
            .writeText("GOOGLE_CLIENT_ID=${id.get()}\nGOOGLE_CLIENT_SECRET=${secret.get()}\n")
    }
}
sourceSets.main { resources.srcDir(cloudConfig) }
