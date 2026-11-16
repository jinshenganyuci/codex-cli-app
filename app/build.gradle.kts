import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** A private first-install artifact only. Never embed an SSH private key or provider credential. */
abstract class PrepareBootstrapAssets : DefaultTask() {
    @get:Input abstract val bootstrapPath: Property<String>
    @get:Input abstract val sourceRoot: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val directory = outputDirectory.get().asFile
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare bootstrap assets" }
        val target = directory.resolve("codex-bootstrap.json")
        check(!target.exists() || target.delete()) { "Cannot clear previous bootstrap asset" }
        val configured = bootstrapPath.get()
        if (configured.isBlank()) return
        val input = File(configured)
        check(input.isAbsolute && input.isFile && input.length() in 1..65536) { "Invalid private bootstrap input" }
        val root = File(sourceRoot.get()).canonicalFile.toPath()
        check(!input.canonicalFile.toPath().startsWith(root)) { "Private bootstrap input must be outside the source tree" }
        val bytes = input.readBytes()
        try {
            // Parse without ever including raw input or parser exceptions in build output.
            val valid = try {
                val value = groovy.json.JsonSlurper().parse(bytes) as? Map<*, *>
                value != null && value.keys == setOf("schemaVersion", "inviteId", "enrollment", "connection", "workspace") &&
                    value["schemaVersion"] == 1 &&
                    (value["enrollment"] as? Map<*, *>)?.keys == setOf("host", "port", "user", "password", "fingerprint", "hostKeyAlgorithm") &&
                    (value["connection"] as? Map<*, *>)?.keys == setOf("host", "port", "user", "fingerprint", "name", "hostKeyAlgorithm", "codexPath")
            } catch (_: Exception) { false }
            check(valid) { "Invalid private bootstrap schema" }
            target.writeBytes(bytes)
            target.setReadable(false, false); target.setReadable(true, true)
            target.setWritable(false, false); target.setWritable(true, true)
        } finally { bytes.fill(0) }
    }
}

val prepareBootstrapAssets = tasks.register<PrepareBootstrapAssets>("prepareBootstrapAssets") {
    bootstrapPath.set(providers.environmentVariable("CODEX_MOBILE_BOOTSTRAP_FILE").orElse(""))
    sourceRoot.set(rootProject.projectDir.absolutePath)
    outputDirectory.set(layout.buildDirectory.dir("generated/private-bootstrap/assets"))
    // Always clear/recreate the asset. A normal incremental build after a private build must be clean.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
android {
    namespace = "com.nanci.codexremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nanci.codexremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.2.9"
        testInstrumentationRunner = providers.gradleProperty("codexTestRunner")
            .orElse("com.nanci.codexremote.test.ShareInstallInstrumentation").get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testBuildType = "release"
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
// Production releases retain defaultConfig. The private enrollment QA package
// can reuse the current installed version to test upgrade without downgrading it.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareBootstrapAssets, PrepareBootstrapAssets::outputDirectory)
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        if (System.getenv("CODEX_MOBILE_TEST_BASE") == "1") {
            variant.outputs.forEach { output ->
                output.versionCode.set(7)
                output.versionName.set("0.2.5-update-test")
            }
        }
    }
    onVariants(selector().withBuildType("release")) { variant ->
        if (System.getenv("CODEX_MOBILE_BOOTSTRAP_TEST_BASE") == "1") {
            check(!System.getenv("CODEX_MOBILE_BOOTSTRAP_FILE").isNullOrBlank()) { "Enrollment QA requires a private test invitation" }
            variant.outputs.forEach { output ->
                output.versionCode.set(10)
                output.versionName.set("0.2.8-invite-test")
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
