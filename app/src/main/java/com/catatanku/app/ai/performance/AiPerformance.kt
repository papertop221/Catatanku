package com.catatanku.app.ai.performance

import com.catatanku.app.domain.model.AiAction
import kotlin.math.ceil

enum class AiBackendKind(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
}

/**
 * Profil batas KV cache (`EngineConfig.maxNumTokens`) dan panjang masukan yang aman.
 * Nilai 2.048, 3.072, dan 4.096 diuji pada Moto G86 Power 5G; 2.048 terbukti tercepat dan paling hemat RAM
 * untuk catatan harian, sedangkan 3.072 / 4.096 tersedia untuk konteks panjang bila RAM memadai.
 */
enum class ContextProfile(val id: String, val label: String, val maxNumTokens: Int, val maxInputChars: Int) {
    HEMAT("hemat", "Hemat", 2048, 2400),
    SEIMBANG("seimbang", "Seimbang", 3072, 4200),
    LONG_CONTEXT("long", "Panjang", 4096, 6000);

    companion object {
        fun byId(id: String?): ContextProfile = entries.firstOrNull { it.id == id } ?: HEMAT
    }
}

/**
 * Anggaran keluaran per tugas (`ConversationConfig.maxOutputToken`) dan kebijakan MTP per tugas.
 * Kebijakan MTP didasarkan pada hasil benchmark Moto G86 Power 5G:
 * - Mempercepat 25–53% pada tugas terstruktur/suntingan (Ringkas, Perbaiki ejaan, Rapikan struktur, Daftar tugas)
 * - Memperlambat 21–39% pada tugas sangat singkat (Tag, Judul) dan netral pada Lanjutkan tulisan
 * Oleh karena itu `Engine` dibuka dengan mode `ADAPTIVE`, lalu tiap `Conversation` memilih `ADAPTIVE` atau `OFF`
 * tanpa memuat ulang engine.
 */
enum class AiTaskBudget(val taskKey: String, val maxOutputTokens: Int, val useMtp: Boolean) {
    TITLE("title", 48, useMtp = false),
    TAG("tag", 64, useMtp = false),
    EXPAND_SEARCH("expand", 96, useMtp = false),
    PLANNER("planner", 160, useMtp = false),
    REMINDER("reminder", 128, useMtp = true),
    SUMMARIZE("summarize", 192, useMtp = true),
    TASKS("tasks", 256, useMtp = true),
    FIX("fix", 512, useMtp = true),
    TIDY("tidy", 512, useMtp = true),
    CONTINUE("continue", 256, useMtp = false),
    CUSTOM("custom", 512, useMtp = true),
    ASK("ask", 256, useMtp = true),
    RECAP("recap", 320, useMtp = true),
    ORGANIZE("organize", 128, useMtp = false),
    MEDIA_READ("media_read", 512, useMtp = false),
    MEDIA_TRANSCRIBE("media_transcribe", 512, useMtp = false);

    companion object {
        fun forAction(action: AiAction): AiTaskBudget = when (action) {
            AiAction.Title -> TITLE
            AiAction.Summarize -> SUMMARIZE
            AiAction.Tasks -> TASKS
            AiAction.Fix -> FIX
            AiAction.Tidy -> TIDY
            AiAction.Continue -> CONTINUE
            AiAction.Custom -> CUSTOM
        }
    }
}

enum class ProfileSource(val label: String) {
    VERIFIED_BENCHMARK("disesuaikan untuk HP ini"),
    DEVICE_CACHE("hasil ukur HP ini"),
    CONSERVATIVE_DEFAULT("bawaan hemat CPU"),
    USER_OVERRIDE("pilihan manual"),
}

data class DeviceFingerprint(
    val soc: String,
    val androidSdk: Int,
    val runtimeVersion: String,
    val modelId: String,
) {
    val storageKey: String
        get() = listOf(soc.lowercase().trim(), androidSdk.toString(), runtimeVersion.trim(), modelId.trim())
            .joinToString("|")
}

data class AiRuntimeProfile(
    val backend: AiBackendKind,
    val cpuThreads: Int,
    val visionBackend: AiBackendKind,
    val audioBackend: AiBackendKind,
    val contextProfile: ContextProfile,
    val enableMtpEngine: Boolean,
    val source: ProfileSource,
    val fingerprint: DeviceFingerprint,
)

data class AiBenchmarkMetric(
    val timestampMs: Long,
    val taskKey: String,
    val backendLabel: String,
    val cpuThreads: Int,
    val mtpActive: Boolean,
    val maxNumTokens: Int,
    val maxOutputTokens: Int,
    val loadMs: Double,
    val isColdLoad: Boolean,
    val ttftMs: Double,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokPerSec: Double,
    val decodeTokPerSec: Double,
    val totalMs: Double,
    val peakRssKb: Long,
    val currentRssKb: Long,
    val thermalStatus: Int,
    val batteryTempTenthsC: Int,
    val success: Boolean,
)

data class AiTaskSummary(
    val taskKey: String,
    val count: Int,
    val medianTotalMs: Double,
    val p95TotalMs: Double,
    val medianTtftMs: Double,
    val p95TtftMs: Double,
    val medianPrefillTps: Double,
    val medianDecodeTps: Double,
    val maxPeakRssKb: Long,
)

object BenchmarkStats {
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = (ceil(p.coerceIn(0.0, 1.0) * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    fun summarize(taskKey: String, metrics: List<AiBenchmarkMetric>): AiTaskSummary? {
        val ok = metrics.filter { it.taskKey == taskKey && it.success }
        if (ok.isEmpty()) return null
        return AiTaskSummary(
            taskKey = taskKey,
            count = ok.size,
            medianTotalMs = percentile(ok.map { it.totalMs }, 0.5),
            p95TotalMs = percentile(ok.map { it.totalMs }, 0.95),
            medianTtftMs = percentile(ok.map { it.ttftMs }, 0.5),
            p95TtftMs = percentile(ok.map { it.ttftMs }, 0.95),
            medianPrefillTps = percentile(ok.map { it.prefillTokPerSec }, 0.5),
            medianDecodeTps = percentile(ok.map { it.decodeTokPerSec }, 0.5),
            maxPeakRssKb = ok.maxOf { it.peakRssKb },
        )
    }
}

/**
 * Foto kondisi memori fisik aktual (tanpa menghitung swap / RAM Boost).
 */
data class MemorySnapshot(
    val physicalTotalBytes: Long,
    val availBytes: Long,
    val lowMemory: Boolean,
    val lastTrimLevel: Int,
    val isBackground: Boolean,
    val wantVision: Boolean,
    val wantAudio: Boolean,
    val wantGpu: Boolean,
    val modelEstimatedBytes: Long,
) {
    val physicalRamGb: Int
        get() = if (physicalTotalBytes <= 0L) 0 else ceil(physicalTotalBytes / 1_073_741_824.0).toInt()
}

data class MemoryDecision(
    val canLoad: Boolean,
    val shortageReason: String?,
    val recommendedContext: ContextProfile,
    val idleTimeoutMs: Long,
    val preferCpuOverGpu: Boolean,
)

/**
 * Kebijakan memori adaptif: memakai RAM fisik aktual, `availMem`, `lowMemory`, sinyal `onTrimMemory`,
 * status foreground/background, dan kebutuhan multimodal. Tidak pernah menghitung RAM Boost/swap.
 */
object MemoryPolicy {
    const val MULTIMODAL_MARGIN_BYTES = 250_000_000L
    const val GPU_EXTRA_MARGIN_BYTES = 200_000_000L
    const val MIN_AVAIL_RATIO_PERCENT = 40L
    const val IDLE_FOREGROUND_COMFORT_MS = 3 * 60_000L
    const val IDLE_TIGHT_MS = 30_000L
    const val IDLE_HIDDEN_MS = 20_000L

    // Nilai TrimMemory dari Android ComponentCallbacks2 disalin sebagai konstanta agar tetap murni Kotlin.
    const val TRIM_UI_HIDDEN = 20
    const val TRIM_RUNNING_LOW = 10
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_BACKGROUND = 40

    fun evaluate(
        snap: MemorySnapshot,
        preferredContext: ContextProfile,
        userLightMode: Boolean,
    ): MemoryDecision {
        val ramGb = snap.physicalRamGb
        val tightDevice = userLightMode || (ramGb in 1..6) || (snap.availBytes in 1 until 1_600_000_000L)
        val recommendedContext = when {
            userLightMode -> ContextProfile.HEMAT
            snap.lowMemory || snap.lastTrimLevel >= TRIM_RUNNING_LOW -> ContextProfile.HEMAT
            snap.physicalTotalBytes > 0 && ramGb < 7 -> ContextProfile.HEMAT
            snap.availBytes in 1 until 1_700_000_000L -> ContextProfile.HEMAT
            snap.availBytes in 1 until 2_400_000_000L && preferredContext == ContextProfile.LONG_CONTEXT -> ContextProfile.SEIMBANG
            else -> preferredContext
        }

        val idleMs = when {
            snap.isBackground -> IDLE_HIDDEN_MS
            tightDevice || snap.lowMemory -> IDLE_TIGHT_MS
            else -> IDLE_FOREGROUND_COMFORT_MS
        }

        if (snap.physicalTotalBytes <= 0L) {
            return MemoryDecision(
                canLoad = true,
                shortageReason = null,
                recommendedContext = recommendedContext,
                idleTimeoutMs = idleMs,
                preferCpuOverGpu = false,
            )
        }

        val baseNeed = snap.modelEstimatedBytes +
            (if (snap.wantVision || snap.wantAudio) MULTIMODAL_MARGIN_BYTES else 0L)
        val cpuRequiredAvail = baseNeed * MIN_AVAIL_RATIO_PERCENT / 100L
        val gpuRequiredAvail = (baseNeed + GPU_EXTRA_MARGIN_BYTES) * MIN_AVAIL_RATIO_PERCENT / 100L

        val preferCpuOverGpu = snap.wantGpu && !snap.wantVision &&
            (snap.availBytes < gpuRequiredAvail || snap.lowMemory)

        val effectiveRequired = if (snap.wantGpu && !preferCpuOverGpu) gpuRequiredAvail else cpuRequiredAvail
        if (snap.lowMemory || snap.availBytes < effectiveRequired) {
            val needGb = (baseNeed + if (snap.wantGpu && !preferCpuOverGpu) GPU_EXTRA_MARGIN_BYTES else 0L) / 1e9
            return MemoryDecision(
                canLoad = false,
                shortageReason = "RAM HP sedang penuh (tersedia ±%.1f GB, model butuh ±%.1f GB). Tutup aplikasi lain lalu coba lagi"
                    .format(snap.availBytes / 1e9, needGb),
                recommendedContext = ContextProfile.HEMAT,
                idleTimeoutMs = IDLE_TIGHT_MS,
                preferCpuOverGpu = true,
            )
        }

        return MemoryDecision(
            canLoad = true,
            shortageReason = null,
            recommendedContext = recommendedContext,
            idleTimeoutMs = idleMs,
            preferCpuOverGpu = preferCpuOverGpu,
        )
    }

    fun isUrgentTrim(level: Int): Boolean =
        level >= TRIM_BACKGROUND || level == TRIM_RUNNING_LOW || level == TRIM_RUNNING_CRITICAL
}

/**
 * Pemilihan profil backend berdasarkan SoC, versi Android, versi runtime, dan model.
 * GPU/MTP hanya diaktifkan otomatis bila perangkat telah lolos benchmark nyata.
 */
object DeviceProfileSelector {
    private val MOTO_G86_POWER_SOC = setOf("mt6878", "dimensity 7300", "dimensity 7300-energy")

    fun select(
        fingerprint: DeviceFingerprint,
        cachedProfile: AiRuntimeProfile?,
        userBackendOverride: AiBackendKind?,
        userContextOverride: ContextProfile?,
        userLightMode: Boolean,
        supportsMtpModel: Boolean = true,
    ): AiRuntimeProfile {
        val base = when {
            userBackendOverride != null -> {
                val baseCtx = userContextOverride ?: if (userLightMode) ContextProfile.HEMAT else ContextProfile.HEMAT
                AiRuntimeProfile(
                    backend = userBackendOverride,
                    cpuThreads = 4,
                    visionBackend = if (isMotoDimensity7300(fingerprint.soc)) AiBackendKind.GPU else userBackendOverride,
                    audioBackend = AiBackendKind.CPU,
                    contextProfile = baseCtx,
                    enableMtpEngine = supportsMtpModel && (cachedProfile != null || isMotoDimensity7300(fingerprint.soc)),
                    source = ProfileSource.USER_OVERRIDE,
                    fingerprint = fingerprint,
                )
            }
            cachedProfile != null && cachedProfile.fingerprint.storageKey == fingerprint.storageKey -> {
                cachedProfile.copy(
                    contextProfile = userContextOverride ?: if (userLightMode) ContextProfile.HEMAT else cachedProfile.contextProfile,
                    source = ProfileSource.DEVICE_CACHE,
                )
            }
            isMotoDimensity7300(fingerprint.soc) && fingerprint.modelId == "gemma4-e2b" -> {
                // Terukur pada Moto G86 Power 5G (16 Nov 2026):
                // - Teks: CPU-4 (4x A78) + KV 2.048 mengungguli GPU (1s init vs 7,9s, RSS 1,69 GB vs 2,27 GB, Ringkas 9,1s vs 10,2s).
                // - Vision: GPU memangkas OCR 26s -> 11,5s (-56%) sekaligus lebih hemat 450 MB RSS dibanding CPU-1600px.
                // - Audio: Gemma 4 E2B hanya mendukung CPU.
                // - MTP: ADAPTIVE pada engine + aktif per-tugas untuk Ringkas/Ejaan/Rapikan/Tugas.
                AiRuntimeProfile(
                    backend = AiBackendKind.CPU,
                    cpuThreads = 4,
                    visionBackend = AiBackendKind.GPU,
                    audioBackend = AiBackendKind.CPU,
                    contextProfile = if (userLightMode) ContextProfile.HEMAT else (userContextOverride ?: ContextProfile.HEMAT),
                    enableMtpEngine = supportsMtpModel,
                    source = ProfileSource.VERIFIED_BENCHMARK,
                    fingerprint = fingerprint,
                )
            }
            else -> {
                // Perangkat belum dibenchmark: konservatif CPU-4, MTP mati sampai terukur.
                AiRuntimeProfile(
                    backend = AiBackendKind.CPU,
                    cpuThreads = 4,
                    visionBackend = AiBackendKind.CPU,
                    audioBackend = AiBackendKind.CPU,
                    contextProfile = if (userLightMode) ContextProfile.HEMAT else (userContextOverride ?: ContextProfile.HEMAT),
                    enableMtpEngine = false,
                    source = ProfileSource.CONSERVATIVE_DEFAULT,
                    fingerprint = fingerprint,
                )
            }
        }
        return if (userLightMode) base.copy(contextProfile = ContextProfile.HEMAT) else base
    }

    fun isMotoDimensity7300(soc: String): Boolean {
        val norm = soc.lowercase().trim()
        return MOTO_G86_POWER_SOC.any { it in norm }
    }
}
