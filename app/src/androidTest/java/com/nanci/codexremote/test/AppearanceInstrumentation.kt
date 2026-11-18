package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.ui.theme.AppColorMode
import com.nanci.codexremote.ui.theme.AppearancePreferences
import com.nanci.codexremote.ui.theme.NativeAppTheme
import com.nanci.codexremote.ui.theme.NativeColors
import com.nanci.codexremote.ui.theme.NativeTheme
import com.nanci.codexremote.ui.theme.nativeColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Isolated preference and production Compose-theme checks. Does not change system night mode,
 * real appearance preferences, connection settings, or issue model requests.
 * Settings clicks, process restarts and actual system-bar appearance are verified separately.
 */
class AppearanceInstrumentation : Instrumentation() {
    private val report = JSONObject()
    private val checks = JSONArray()
    private val themeResults = JSONArray()
    private var stage = "setup"
    private var activity: MainActivity? = null
    private var output: File? = null
    private var isolatedPreferences: SharedPreferences? = null
    private val preferenceName = "appearance-regression-${UUID.randomUUID()}"
    private var originalAppearance: String? = null
    private var originalConnections: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var success = false
        try {
            check(targetContext.packageName == "com.nanci.codexremote")
            originalAppearance = preferenceDigest("appearance")
            originalConnections = preferenceDigest("connections-v2")
            val external = requireNotNull(targetContext.getExternalFilesDir(null))
            output = File(external, "appearance-regression-${UUID.randomUUID()}").also { check(it.mkdirs()) }
            preferenceChecks()
            themeChecks()
            success = true
        } catch (failure: Throwable) {
            report.put("failure_type", failure.javaClass.simpleName)
            report.put("failure_location", failure.stackTrace.filter { it.className.startsWith("com.nanci.") }
                .joinToString("\n"))
        } finally {
            val activityClosed = runCatching {
                runOnMainSync { activity?.finish(); activity = null }
                waitForIdleSync()
            }.isSuccess
            val preferencesRemoved = runCatching {
                isolatedPreferences?.let { check(it.edit().clear().commit()) }
                check(targetContext.deleteSharedPreferences(preferenceName))
            }.isSuccess
            val appearancePreserved = originalAppearance?.let { expected ->
                runCatching { preferenceDigest("appearance") == expected }.getOrDefault(false)
            } ?: false
            val connectionsPreserved = originalConnections?.let { expected ->
                runCatching { preferenceDigest("connections-v2") == expected }.getOrDefault(false)
            } ?: false
            success = success && activityClosed && preferencesRemoved && appearancePreserved && connectionsPreserved
            report.put("checks", checks).put("themes", themeResults)
                .put("activity_closed", activityClosed)
                .put("isolated_preferences_removed", preferencesRemoved)
                .put("real_appearance_preserved", appearancePreserved)
                .put("connections_preserved", connectionsPreserved)
                .put("system_settings_changed", false)
                .put("model_turns_started", false)
                .put("status", if (success) "passed" else "failed")
                .put("stage", stage)
            val reportSaved = runCatching {
                File(requireNotNull(output), "results.json").writeText(report.toString(2))
            }.isSuccess
            if (!reportSaved) {
                success = false
                report.put("status", "failed").put("report_write_failed", true)
            }
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("status", report.optString("status"))
                putString("stage", stage)
                putString("output", output?.absolutePath.orEmpty())
                putString("report", report.toString())
                putString("stream", "\nAPPEARANCE_CHECK ${report.optString("status")} ($stage)\n")
            })
        }
    }

    private fun preferenceChecks() {
        step("isolated-preferences")
        val prefs = targetContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        isolatedPreferences = prefs
        check(prefs.all.isEmpty())
        check(AppearancePreferences(prefs).read() == AppColorMode.Light)
        checks.put("new_preferences_default_to_light")

        AppearancePreferences(prefs).save(AppColorMode.Dark)
        // A commit with no edits waits for previous apply writes; only the isolated file is touched.
        check(prefs.edit().commit())
        check(AppearancePreferences(targetContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)).read() == AppColorMode.Dark)
        check(prefs.getString("color_mode", null) == AppColorMode.Dark.storedValue)
        checks.put("dark_survives_new_preferences_store")

        AppearancePreferences(prefs).save(AppColorMode.Light)
        check(prefs.edit().commit())
        check(AppearancePreferences(prefs).read() == AppColorMode.Light)
        check(prefs.getString("color_mode", null) == AppColorMode.Light.storedValue)
        checks.put("explicit_light_restores_light")

        for (invalid in listOf("future-mode", "", "DARK")) {
            check(prefs.edit().putString("color_mode", invalid).commit())
            check(AppearancePreferences(prefs).read() == AppColorMode.Light)
        }
        checks.put("unsupported_string_values_fall_back_to_light")
        check(prefs.edit().putInt("color_mode", 1).commit())
        check(AppearancePreferences(prefs).read() == AppColorMode.Light)
        checks.put("wrong_preference_type_falls_back_to_light")
    }

    private fun themeChecks() {
        step("compose-theme")
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        for (night in listOf(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)) {
            renderAndCheck(night, explicitDark = null)
            renderAndCheck(night, explicitDark = true)
            renderAndCheck(night, explicitDark = false)
        }
        checks.put("default_light_and_explicit_modes_ignore_system_night_mode")
    }

    private data class ObservedTheme(val systemDark: Boolean, val native: NativeColors, val material: ColorScheme)

    private fun renderAndCheck(nightMode: Int, explicitDark: Boolean?) {
        val observed = AtomicReference<ObservedTheme?>()
        val label = "${if (nightMode == Configuration.UI_MODE_NIGHT_YES) "system_dark" else "system_light"}:" +
            when (explicitDark) { null -> "default"; true -> "explicit_dark"; false -> "explicit_light" }
        val screen = requireNotNull(activity)
        runOnMainSync {
            val simulated = Configuration(screen.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            screen.setContent {
                CompositionLocalProvider(LocalConfiguration provides simulated) {
                    if (explicitDark == null) {
                        // Deliberately omit darkTheme: this catches accidental system-following defaults.
                        NativeAppTheme(highContrast = false) { ThemeProbe(label, observed) }
                    } else {
                        NativeAppTheme(darkTheme = explicitDark, highContrast = false) { ThemeProbe(label, observed) }
                    }
                }
            }
        }
        waitForIdleSync()
        waitFor { observed.get() != null }
        val actual = requireNotNull(observed.get())
        val dark = explicitDark == true
        check(actual.systemDark == (nightMode == Configuration.UI_MODE_NIGHT_YES)) { "$label fixture night mode was not observed" }
        check(actual.native == nativeColors(dark, false)) { "$label native palette does not match selected mode" }
        check(actual.native.background == if (dark) Color(0xFF303030) else Color.White)
        check(actual.native.surface == if (dark) Color(0xFF212121) else Color.White)
        check(actual.native.ink == if (dark) Color.White else Color(0xFF0D0D0D))
        check(actual.material.background == actual.native.background)
        check(actual.material.surface == actual.native.surface)
        check(actual.material.onBackground == actual.native.ink && actual.material.onSurface == actual.native.ink)
        check(actual.material.primary == actual.native.ink && actual.material.onPrimary == actual.native.surface)
        check(actual.material.secondary == actual.native.accent && actual.material.surfaceVariant == actual.native.field)
        check(actual.material.error == actual.native.danger && actual.material.outline == actual.native.border)
        themeResults.put(JSONObject().put("case", label)
            .put("simulated_system_dark", actual.systemDark).put("native_dark", actual.native.isDark)
            .put("native_background", colorHex(actual.native.background)).put("native_surface", colorHex(actual.native.surface))
            .put("native_ink", colorHex(actual.native.ink)).put("material_background", colorHex(actual.material.background))
            .put("material_surface", colorHex(actual.material.surface)).put("material_on_surface", colorHex(actual.material.onSurface)))
    }

    @Composable
    private fun ThemeProbe(label: String, observed: AtomicReference<ObservedTheme?>) {
        val systemDark = isSystemInDarkTheme()
        val palette = NativeTheme.colors
        val material = MaterialTheme.colorScheme
        SideEffect { observed.set(ObservedTheme(systemDark, palette, material)) }
        Column(Modifier.fillMaxSize().background(palette.background).padding(24.dp)) {
            Text("Appearance regression", color = palette.ink)
            Text(label, color = material.onBackground)
        }
    }

    private fun colorHex(color: Color): String = "#%08X".format(color.toArgb())

    /** Digests stay in memory and are never included in the report or a failure message. */
    private fun preferenceDigest(name: String): String {
        val values = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
        val encoded = values.toSortedMap().entries.joinToString("\u0000") { (key, value) ->
            val stable = if (value is Set<*>) value.map { it.toString() }.sorted().joinToString("\u0002") else value.toString()
            "$key\u0001${value?.javaClass?.name}\u0001$stable"
        }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun step(value: String) {
        stage = value
        sendStatus(1, Bundle().apply { putString("stream", "\nAPPEARANCE_CHECK $value\n") })
    }

    private fun waitFor(timeoutMillis: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        error("Appearance composition did not complete")
    }
}
