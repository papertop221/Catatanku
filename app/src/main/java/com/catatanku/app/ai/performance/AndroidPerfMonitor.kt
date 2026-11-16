package com.catatanku.app.ai.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * Pembaca metrik perangkat Android nyata (RAM fisik tanpa RAM Boost/swap, VmHWM/VmRSS,
 * status termal, suhu baterai, dan fingerprint SoC) serta penyimpanan profil benchmark per perangkat.
 */
class AndroidPerfMonitor(private val context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun socName(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else ""
        return soc.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: Build.HARDWARE.orEmpty().ifBlank { "unknown" }
    }

    fun memorySnapshot(
        lastTrimLevel: Int,
        isBackground: Boolean,
        wantVision: Boolean,
        wantAudio: Boolean,
        wantGpu: Boolean,
        modelEstimatedBytes: Long,
    ): MemorySnapshot {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemorySnapshot(
            physicalTotalBytes = mi.totalMem,
            availBytes = mi.availMem,
            lowMemory = mi.lowMemory,
            lastTrimLevel = lastTrimLevel,
            isBackground = isBackground,
            wantVision = wantVision,
            wantAudio = wantAudio,
            wantGpu = wantGpu,
            modelEstimatedBytes = modelEstimatedBytes,
        )
    }

    fun processRssKb(): Pair<Long, Long> {
        var hwm = 0L
        var rss = 0L
        runCatching {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmHWM:") -> hwm = line.replace(Regex("\\D+"), "").toLongOrNull() ?: 0L
                    line.startsWith("VmRSS:") -> rss = line.replace(Regex("\\D+"), "").toLongOrNull() ?: 0L
                }
            }
        }
        return hwm to rss
    }

    fun thermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) {
        runCatching { pm?.currentThermalStatus ?: -1 }.getOrDefault(-1)
    } else -1

    fun batteryTempTenthsC(): Int = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    }.getOrDefault(-1)

    fun fingerprint(modelId: String, runtimeVersion: String = "litertlm-0.19.1"): DeviceFingerprint =
        DeviceFingerprint(
            soc = socName(),
            androidSdk = Build.VERSION.SDK_INT,
            runtimeVersion = runtimeVersion,
            modelId = modelId,
        )
}

class PerformanceProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_perf", Context.MODE_PRIVATE)

    fun load(fp: DeviceFingerprint): AiRuntimeProfile? {
        val key = fp.storageKey
        val backendName = prefs.getString("${key}_backend", null) ?: return null
        val backend = runCatching { AiBackendKind.valueOf(backendName) }.getOrNull() ?: return null
        val threads = prefs.getInt("${key}_threads", 4).coerceIn(2, 8)
        val vision = runCatching {
            AiBackendKind.valueOf(prefs.getString("${key}_vision", backend.name)!!)
        }.getOrDefault(AiBackendKind.CPU)
        val ctx = ContextProfile.byId(prefs.getString("${key}_ctx", ContextProfile.HEMAT.id))
        val mtp = prefs.getBoolean("${key}_mtp", false)
        return AiRuntimeProfile(
            backend = backend,
            cpuThreads = threads,
            visionBackend = vision,
            audioBackend = AiBackendKind.CPU,
            contextProfile = ctx,
            enableMtpEngine = mtp,
            source = ProfileSource.DEVICE_CACHE,
            fingerprint = fp,
        )
    }

    fun save(profile: AiRuntimeProfile) {
        val key = profile.fingerprint.storageKey
        prefs.edit()
            .putString("${key}_backend", profile.backend.name)
            .putInt("${key}_threads", profile.cpuThreads)
            .putString("${key}_vision", profile.visionBackend.name)
            .putString("${key}_ctx", profile.contextProfile.id)
            .putBoolean("${key}_mtp", profile.enableMtpEngine)
            .apply()
    }
}
