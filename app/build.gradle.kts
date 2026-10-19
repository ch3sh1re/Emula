import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val motorolaStExperiment = providers.gradleProperty("emula.motorolaStExperiment")
    .map(String::toBooleanStrict).getOrElse(false)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun signingSecret(name: String): String =
    keystoreProperties.getProperty(name)
        ?: keystoreProperties.getProperty("${name}File")
            ?.let { rootProject.file(it).readText().trim() }
        ?: error("Missing $name or ${name}File in keystore.properties")

android {
    namespace = "dev.dunno.emula"
    compileSdk = providers.gradleProperty("emula.compileSdk")
        .orNull
        ?.toInt()
        ?: 36

    defaultConfig {
        applicationId = "dev.dunno.emula"
        minSdk = 35
        targetSdk = 36
        versionCode = 33
        versionName = "1.0.13"
        buildConfigField("boolean", "MOTOROLA_ST_EXPERIMENT", "false")
        buildConfigField("boolean", "MOTOROLA_ST_RAW_COMPATIBILITY", "true")
        testInstrumentationRunner = "dev.dunno.emula.UidEditorTestInstrumentation"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = signingSecret("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = signingSecret("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "MOTOROLA_ST_EXPERIMENT", motorolaStExperiment.toString())
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        create("internalSt") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "MOTOROLA_ST_EXPERIMENT", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("internalSt")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(30)
            output.versionName.set("1.0.11-motorola-st-test1")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
