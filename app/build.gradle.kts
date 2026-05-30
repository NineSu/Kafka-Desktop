plugins {
    alias(libs.plugins.javafx)
    application
}

dependencies {
    implementation(project(":core-kafka"))
    implementation(project(":core-filter"))
    implementation(project(":core-storage"))
    implementation(project(":ui-common"))
    implementation(libs.koin.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation("io.insert-koin:koin-test:3.5.6")
    testImplementation("io.insert-koin:koin-test-junit5:3.5.6")
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    mainClass.set("com.kdt.app.MainKt")
}

// ---- Packaging: self-contained native installer via jpackage (iter-12) ----
// Bundles app + all runtime jars (incl. platform JavaFX) + a full JDK runtime, run on the
// classpath (the Main.kt launcher pattern lets JavaFX start without the module path).
//
// We build the .app image with jpackage, then assemble the .dmg ourselves with a correct
// "Applications" symlink. jpackage's built-in --type dmg layout creates a broken ":Applications"
// file (a jpackage quirk) with no valid drag-install target, so we avoid it. macOS-only;
// other platforms get their own packaging path in CI (iter-13).

val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0" // -PappVersion overrides (CI passes the tag)

val jpackageInputDir = layout.buildDirectory.dir("jpackage/input")

val copyRuntimeDeps by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Collect the app jar + all runtime dependencies into the jpackage input dir."
    val into = jpackageInputDir.get().asFile
    doFirst { into.deleteRecursively(); into.mkdirs() }
    from(configurations.runtimeClasspath)
    from(tasks.named("jar"))
    into(jpackageInputDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Build a self-contained native installer for the current OS (macOS .dmg / Windows .msi / Linux .deb)."
    dependsOn(copyRuntimeDeps)

    val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val pkgDir = layout.buildDirectory.dir("jpackage").get().asFile.absolutePath
    val input = jpackageInputDir.get().asFile.absolutePath
    val distDir = layout.buildDirectory.dir("jpackage/dist").get().asFile
    val macScript = layout.projectDirectory.file("scripts/package-mac-dmg.sh").asFile.absolutePath
    val osName = System.getProperty("os.name").lowercase()

    doFirst {
        val jar = mainJarName.get()
        if (osName.contains("mac") || osName.contains("darwin")) {
            // macOS: app-image + hand-assembled .dmg (avoids jpackage's broken :Applications symlink).
            commandLine("bash", macScript, jar, appVersion, pkgDir, input)
        } else {
            // Windows / Linux: jpackage's built-in installer types work directly.
            distDir.deleteRecursively(); distDir.mkdirs()
            val cmd = mutableListOf(
                "jpackage",
                "--name", "Kafka Desktop",
                "--app-version", appVersion,
                "--input", input,
                "--dest", distDir.absolutePath,
                "--main-jar", jar,
                "--main-class", "com.kdt.app.MainKt",
            )
            if (osName.contains("win")) {
                cmd += listOf("--type", "msi", "--win-shortcut", "--win-menu", "--win-dir-chooser")
            } else {
                cmd += listOf("--type", "deb", "--linux-package-name", "kafka-desktop", "--linux-shortcut")
            }
            commandLine(cmd)
        }
    }
}
