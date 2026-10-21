plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.train"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.train"
        minSdk = 24
        targetSdk = 34

        val vcProp = (project.findProperty("vc") as String?)?.toIntOrNull()
        versionCode = vcProp ?: (System.currentTimeMillis() / 60000L).toInt()
        versionName = "1.0"

        // GitHub Actions -PgithubRepo=<owner/repo> deta hai (auto-update isi repo ke releases se hota hai)
        val repoProp = (project.findProperty("githubRepo") as String?) ?: ""
        buildConfigField("String", "UPDATE_REPO", "\"$repoProp\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Keystore GitHub Secrets se build ke waqt app/lion-release.jks me banti hai (repo me nahi).
    // Secrets na hon to debug key se sign hoti hai (tab update ke liye uninstall karna padega).
    val lionKs = file("lion-release.jks")
    val lionPass = System.getenv("LION_STORE_PASSWORD")
    val lionAlias = System.getenv("LION_KEY_ALIAS") ?: "lion"
    val lionKeyPass = System.getenv("LION_KEY_PASSWORD") ?: lionPass
    if (lionKs.exists() && !lionPass.isNullOrEmpty()) {
        signingConfigs {
            create("lion") {
                storeFile = lionKs
                storePassword = lionPass
                keyAlias = lionAlias
                keyPassword = lionKeyPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("lion")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("lion")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
