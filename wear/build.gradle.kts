import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use(::load)
}
fun signingSecret(name: String): String = signingProperties.getProperty(name)
    ?: signingProperties.getProperty(name + "File")
        ?.let { rootProject.file(it).readText().trim() }
    ?: error("Missing signing property: " + name)

val syncWatchSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        include(
            "dev/dunno/emula/OemNfcController.java",
            "dev/dunno/emula/OemOperationReport.java",
            "dev/dunno/emula/OnePlusVendorNfcController.java",
            "dev/dunno/emula/MotorolaStNfcController.java",
            "dev/dunno/emula/LegacyNfcController.java",
            "dev/dunno/emula/LegacyNfcDiagnostics.java",
            "dev/dunno/emula/RawNciCodec.java",
            "dev/dunno/emula/RawNfcDeviceCatalog.java",
            "dev/dunno/emula/RawOperationReport.java",
            "dev/dunno/emula/UidCodec.kt",
            "dev/dunno/emula/AppLog.kt",
            "dev/dunno/emula/Hex.kt",
            "dev/dunno/emula/SavedCard.kt",
            "dev/dunno/emula/CardCatalog.kt",
            "dev/dunno/emula/CardStore.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/sources/watchCore"))
}

android {
    namespace = "dev.dunno.emula"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.dunno.emula"
        minSdk = 35
        targetSdk = 36
        versionCode = 10001
        versionName = "1.0.0-wear"
        testInstrumentationRunner = "dev.dunno.emula.WatchFlowInstrumentation"
        buildConfigField("boolean", "MOTOROLA_ST_EXPERIMENT", "false")
    }
    signingConfigs {
        if (signingFile.isFile) create("release") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingSecret("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingSecret("keyPassword")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".watchtest"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").java.srcDir(
        layout.buildDirectory.dir("generated/sources/watchCore"),
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    jvmToolchain(17)
    sourceSets.getByName("main").kotlin.srcDir(
        layout.buildDirectory.dir("generated/sources/watchCore"),
    )
}
tasks.named("preBuild") { dependsOn(syncWatchSources) }
dependencies { testImplementation("junit:junit:4.13.2") }
