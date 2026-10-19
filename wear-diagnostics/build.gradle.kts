plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reuse the existing read-only transport and its codecs without compiling either
// activation controller or the phone Activity into the diagnostic APK.
val syncDiagnosticSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        include(
            "dev/dunno/emula/LegacyNfcDiagnostics.java",
            "dev/dunno/emula/RawNciCodec.java",
            "dev/dunno/emula/RawOperationReport.java",
            "dev/dunno/emula/UidCodec.kt",
            "dev/dunno/emula/AppLog.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/sources/diagnostics"))
}

android {
    namespace = "dev.dunno.emula"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dunno.emula.watchprobe"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-watch-probe"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets.getByName("main").java.srcDir(
        layout.buildDirectory.dir("generated/sources/diagnostics"),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets.getByName("main").kotlin.srcDir(
        layout.buildDirectory.dir("generated/sources/diagnostics"),
    )
}

tasks.named("preBuild") {
    dependsOn(syncDiagnosticSources)
}
