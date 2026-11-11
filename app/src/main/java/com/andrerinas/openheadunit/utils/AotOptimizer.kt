package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Automates Ahead-Of-Time (AOT) compilation for DiAuto into native ARM machine code
 * using the Android Runtime (ART) 'speed' compilation filter.
 *
 * Low-resource automotive head units (e.g., Quad-Core Cortex-A7 @ 1.12 GHz with 1-2 GB RAM)
 * suffer from JIT compilation overhead, resulting in UI jitter, audio buffer starvation,
 * and high projection latency. Pre-compiling the entire APK with dex2oat eliminates
 * on-the-fly JIT overhead.
 */
object AotOptimizer {

    private const val PREFS_NAME = "aot_optimization_prefs"
    private const val KEY_LAST_OPTIMIZED_VERSION = "last_optimized_version_code"

    /**
     * Asynchronously triggers Ahead-Of-Time compilation in a background thread
     * so app startup and user interaction are never blocked.
     */
    fun optimizeAsync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return // Pre-ART devices (Dalvik) do not support dex2oat compilation filters
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastOptimizedVersion = prefs.getInt(KEY_LAST_OPTIMIZED_VERSION, -1)
                val currentVersion = BuildConfig.VERSION_CODE

                if (lastOptimizedVersion == currentVersion) {
                    AppLog.d("AotOptimizer: App is already AOT speed-compiled for versionCode $currentVersion")
                    return@launch
                }

                val packageName = context.packageName
                val compileCmd = "cmd package compile -m speed $packageName 2>/dev/null || pm compile -m speed -f $packageName 2>/dev/null"
                var success = false

                // 1. Attempt compilation via libsu Root Shell
                try {
                    val result = Shell.cmd(compileCmd).exec()
                    if (result.isSuccess) {
                        success = true
                    }
                } catch (_: Exception) {}

                // 2. Attempt compilation via SUExecutor
                if (!success) {
                    try {
                        val suExecutor = App.provide(context).suExecutor
                        val exitCode = suExecutor.runShell(compileCmd)
                        if (exitCode == 0) {
                            success = true
                        }
                    } catch (_: Exception) {}
                }

                // 3. Fallback direct execution for userdebug / engineering builds
                if (!success) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", compileCmd))
                        process.waitFor()
                        if (process.exitValue() == 0) {
                            success = true
                        }
                    } catch (_: Exception) {}
                }

                if (success) {
                    prefs.edit().putInt(KEY_LAST_OPTIMIZED_VERSION, currentVersion).apply()
                    AppLog.i("AotOptimizer: Successfully executed Ahead-Of-Time (AOT) compilation for $packageName (speed filter).")
                } else {
                    AppLog.d("AotOptimizer: AOT speed compilation skipped or deferred (root/shell not available).")
                }
            } catch (e: Exception) {
                AppLog.w("AotOptimizer: Exception while checking AOT compilation: ${e.message}")
            }
        }
    }
}
