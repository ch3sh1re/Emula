import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
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
        versionCode = 28
        versionName = "1.0.9"
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
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
