package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.sqrt

/**
 * Streamlined 1-Tap Onboarding for Car Head Units.
 *
 * Replaces the old multi-step wizard with a single cockpit-ready screen:
 * - Pre-configures optimal display resolution, codec and low-latency buffers
 * - Defaults connection to Wireless & USB Android Auto
 * - Batches all runtime permissions into a single 1-tap approval prompt
 * - Tapping "Get Started" applies settings, prompts permissions, and immediately
 *   launches the main dual 1-tap cockpit dashboard.
 */
class OnboardingActivity : BaseActivity() {

    private val settings by lazy { App.provide(this).settings }

    private lateinit var getStartedBtn: MaterialButton
    private lateinit var customSettingsBtn: MaterialButton
    private lateinit var languageBtn: MaterialButton
    private lateinit var permsBadge: TextView
    private lateinit var displaySummary: TextView

    private var selectedSize = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait = false

    // Single permission launcher: requesting all missing permissions in 1 tap
    private val permNormalLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // As soon as the user approves or dismisses the system permission dialogs,
        // finish onboarding immediately so the user lands straight on the dashboard!
        finishOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Match app theme overlays
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (settings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (settings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }

        setContentView(R.layout.activity_onboarding)

        getStartedBtn = findViewById(R.id.onb_get_started_btn)
        customSettingsBtn = findViewById(R.id.onb_custom_settings_btn)
        languageBtn = findViewById(R.id.onb_language_button)
        permsBadge = findViewById(R.id.onb_perms_status_badge)
        displaySummary = findViewById(R.id.onb_display_summary)

        selectedPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        selectedSize = estimateSizePreset()

        if (settings.connectionModes.isEmpty()) {
            settings.connectionModes = setOf(Settings.ConnectionMode.WIFI, Settings.ConnectionMode.USB)
        }

        updateDisplaySummary()
        updatePermissionsStatus()

        languageBtn.text = currentLanguageLabel()
        languageBtn.setOnClickListener { showLanguageDialog() }

        customSettingsBtn.setOnClickListener {
            // Jump directly to Settings for advanced custom configuration
            finishOnboardingInto(R.id.driveSettingsFragment)
        }

        getStartedBtn.setOnClickListener {
            onGetStartedClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsStatus()
    }

    private fun updateDisplaySummary() {
        val m = realMetrics()
        val hevc = VideoDecoder.isHevcSupported()
        val codec = if (hevc) "H.265" else "H.264"
        displaySummary.text = "${m.widthPixels}x${m.heightPixels} (${settings.dpiPixelDensity} dpi, $codec)"
    }

    private fun updatePermissionsStatus() {
        val missing = AppPermissions.missingNormalPermissions(this)
        if (missing.isEmpty()) {
            permsBadge.text = getString(R.string.onb_perms_all_granted)
            permsBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.material_green_700))
        } else {
            permsBadge.text = getString(R.string.onb_perms_1tap_approval)
            permsBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.da_accent))
        }
    }

    private fun onGetStartedClicked() {
        // 1. Accept disclaimer & auto-tune display and system settings
        settings.hasAcceptedDisclaimer = true
        applyDisplaySettings()

        // 2. Check missing normal permissions
        val missing = AppPermissions.missingNormalPermissions(this)
        if (missing.isNotEmpty()) {
            getStartedBtn.isEnabled = false
            getStartedBtn.text = getString(R.string.onb_starting_status)
            // Launch 1-tap batch approval via system dialog
            permNormalLauncher.launch(missing.toTypedArray())
        } else {
            // All permissions already granted! Complete immediately.
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    private fun finishOnboardingInto(destinationId: Int) {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_DESTINATION, destinationId)
        )
        finish()
    }

    override fun onBackPressed() {
        // Back behaves like "Do it later" so the user is not trapped
        deferredThisSession = true
        settings.commit()
        finish()
    }

    // --- Language ---

    private fun currentLanguageLabel(): String {
        val locale = LocaleHelper.stringToLocale(settings.appLanguage)
        return if (locale == null) getString(R.string.system_default)
        else LocaleHelper.getDisplayName(locale)
    }

    private fun showLanguageDialog() {
        val locales = LocaleHelper.getAvailableLocales(this)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.system_default))
        locales.forEach { labels.add(LocaleHelper.getDisplayName(it)) }
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.app_language)
            .setItems(labels.toTypedArray()) { _, which ->
                val newLang = if (which == 0) "" else LocaleHelper.localeToString(locales[which - 1])
                if (newLang != settings.appLanguage) {
                    settings.appLanguage = newLang
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Display Auto-Optimization ---

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> display?.getRealMetrics(metrics)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            else ->
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    private fun estimateSizePreset(): SystemOptimizer.DisplaySizePreset {
        val m = realMetrics()
        val xdpi = if (m.xdpi > 40f) m.xdpi else m.densityDpi.toFloat()
        val ydpi = if (m.ydpi > 40f) m.ydpi else m.densityDpi.toFloat()
        val wIn = m.widthPixels / xdpi
        val hIn = m.heightPixels / ydpi
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        return SystemOptimizer.DisplaySizePreset.values().minByOrNull {
            kotlin.math.abs(it.diagonalInch - diagonal)
        } ?: SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    }

    private fun recommendedDpi(): Int =
        SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait).recommendedDpi

    private fun isUpgrader(): Boolean =
        settings.hasCompletedSetupWizard ||
                settings.onboardingVersion in 1 until CURRENT_ONBOARDING_VERSION

    private fun applyDisplaySettings() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        val panel = realMetrics()
        if (!isUpgrader()) {
            val previousViewMode = settings.viewMode
            settings.resolutionId = SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
            settings.videoCodec = result.recommendedVideoCodec
            settings.viewMode = result.recommendedViewMode
            settings.screenOrientation = result.suggestedOrientation
            if (settings.viewMode != previousViewMode) settings.pendingRendererConfirm = true
        }
        if (settings.dpiPixelDensity == 0) {
            settings.dpiPixelDensity = Settings.DEFAULT_DPI_SMALL
        }
        settings.commit()
    }

    companion object {
        const val CURRENT_ONBOARDING_VERSION = 3
        @Volatile
        var deferredThisSession = false
    }
}
