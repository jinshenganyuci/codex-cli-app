plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.nanci.codexremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nanci.codexremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        getByName("debug") {
            System.getenv("CODEX_MOBILE_SIGNING_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = System.getenv("CODEX_MOBILE_SIGNING_PASSWORD")
                keyAlias = "codex-mobile"
                keyPassword = System.getenv("CODEX_MOBILE_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("debug"); isMinifyEnabled = false }
    }
}
// A real, older installed version for end-to-end system-installer testing only.
// The release variant always retains defaultConfig's production version.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (System.getenv("CODEX_MOBILE_TEST_BASE") == "1") {
            variant.outputs.forEach { output ->
                output.versionCode.set(7)
                output.versionName.set("0.2.5-update-test")
            }
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.github.mwiede:jsch:0.2.25")
}
