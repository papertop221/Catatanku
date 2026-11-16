package com.catatanku.app

import com.catatanku.app.ai.performance.AiBackendKind
import com.catatanku.app.ai.performance.AiBenchmarkMetric
import com.catatanku.app.ai.performance.AiRuntimeProfile
import com.catatanku.app.ai.performance.AiTaskBudget
import com.catatanku.app.ai.performance.BenchmarkStats
import com.catatanku.app.ai.performance.ContextProfile
import com.catatanku.app.ai.performance.DeviceFingerprint
import com.catatanku.app.ai.performance.DeviceProfileSelector
import com.catatanku.app.ai.performance.MemoryPolicy
import com.catatanku.app.ai.performance.MemorySnapshot
import com.catatanku.app.ai.performance.ProfileSource
import com.catatanku.app.security.InputSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceAndMemoryTest {
    private val motoFp = DeviceFingerprint(
        soc = "mt6878",
        androidSdk = 36,
        runtimeVersion = "0.19.1",
        modelId = "gemma4-e2b",
    )

    @Test fun deviceProfileSelectorHonorsMotoBenchmarkConservativeDefaultAndOverrides() {
        val moto = DeviceProfileSelector.select(
            fingerprint = motoFp,
            cachedProfile = null,
            userBackendOverride = null,
            userContextOverride = null,
            userLightMode = false,
        )
        assertEquals(ProfileSource.VERIFIED_BENCHMARK, moto.source)
        assertEquals(AiBackendKind.CPU, moto.backend)
        assertEquals(4, moto.cpuThreads)
        assertEquals(AiBackendKind.GPU, moto.visionBackend)
        assertEquals(AiBackendKind.CPU, moto.audioBackend)
        assertEquals(ContextProfile.HEMAT, moto.contextProfile)
        assertTrue(moto.enableMtpEngine)

        // Perangkat tak dikenal: konservatif CPU-4, tanpa GPU vision & tanpa MTP sampai lolos benchmark.
        val unknownFp = DeviceFingerprint("sm8550", 35, "0.19.1", "gemma4-e2b")
        val unknown = DeviceProfileSelector.select(unknownFp, null, null, null, userLightMode = false)
        assertEquals(ProfileSource.CONSERVATIVE_DEFAULT, unknown.source)
        assertEquals(AiBackendKind.CPU, unknown.backend)
        assertEquals(AiBackendKind.CPU, unknown.visionBackend)
        assertFalse(unknown.enableMtpEngine)

        // Cache hanya terpakai jika fingerprint persis cocok (SoC + SDK + runtime + model).
        val cached = AiRuntimeProfile(
            backend = AiBackendKind.GPU,
            cpuThreads = 4,
            visionBackend = AiBackendKind.GPU,
            audioBackend = AiBackendKind.CPU,
            contextProfile = ContextProfile.SEIMBANG,
            enableMtpEngine = true,
            source = ProfileSource.DEVICE_CACHE,
            fingerprint = unknownFp,
        )
        val cacheHit = DeviceProfileSelector.select(unknownFp, cached, null, null, userLightMode = false)
        assertEquals(ProfileSource.DEVICE_CACHE, cacheHit.source)
        assertEquals(AiBackendKind.GPU, cacheHit.backend)
        assertEquals(ContextProfile.SEIMBANG, cacheHit.contextProfile)

        val bumpedRuntime = unknownFp.copy(runtimeVersion = "0.20.0")
        val cacheMiss = DeviceProfileSelector.select(bumpedRuntime, cached, null, null, userLightMode = false)
        assertEquals(ProfileSource.CONSERVATIVE_DEFAULT, cacheMiss.source)

        // Mode ringan selalu memaksa ContextProfile.HEMAT.
        val light = DeviceProfileSelector.select(unknownFp, cached, null, ContextProfile.LONG_CONTEXT, userLightMode = true)
        assertEquals(ContextProfile.HEMAT, light.contextProfile)
    }

    @Test fun taskBudgetsApplyPerTaskMtpAndMaxOutputTokens() {
        assertFalse(AiTaskBudget.TITLE.useMtp)
        assertFalse(AiTaskBudget.TAG.useMtp)
        assertFalse(AiTaskBudget.EXPAND_SEARCH.useMtp)
        assertFalse(AiTaskBudget.CONTINUE.useMtp)

        assertTrue(AiTaskBudget.SUMMARIZE.useMtp)
        assertTrue(AiTaskBudget.FIX.useMtp)
        assertTrue(AiTaskBudget.TIDY.useMtp)
        assertTrue(AiTaskBudget.TASKS.useMtp)
        assertTrue(AiTaskBudget.REMINDER.useMtp)

        AiTaskBudget.entries.forEach {
            assertTrue(it.name, it.maxOutputTokens in 48..512)
        }
        assertEquals(listOf(2048, 3072, 4096), ContextProfile.entries.map { it.maxNumTokens })
        assertTrue(ContextProfile.entries.all { it.label.isNotBlank() && '(' !in it.label })
        assertTrue(ProfileSource.entries.all { it.label.isNotBlank() && "benchmark" !in it.label.lowercase() })
    }

    @Test fun memoryPolicyUsesPhysicalRamAndSignalsWithoutSwap() {
        // 8 GB fisik (7.4 GB efektif di Android) dengan 3,2 GB tersedia: aman untuk 3072 token, nyaman di foreground.
        val healthy = MemorySnapshot(
            physicalTotalBytes = 7_750_000_000L,
            availBytes = 3_200_000_000L,
            lowMemory = false,
            lastTrimLevel = 0,
            isBackground = false,
            wantVision = false,
            wantAudio = false,
            wantGpu = false,
            modelEstimatedBytes = 1_750_000_000L,
        )
        val dHealthy = MemoryPolicy.evaluate(healthy, ContextProfile.SEIMBANG, userLightMode = false)
        assertTrue(dHealthy.canLoad)
        assertNull(dHealthy.shortageReason)
        assertEquals(ContextProfile.SEIMBANG, dHealthy.recommendedContext)
        assertEquals(MemoryPolicy.IDLE_FOREGROUND_COMFORT_MS, dHealthy.idleTimeoutMs)

        // 6 GB fisik: meski tersedia cukup, disarankan Hemat (2048) & timeout ketat.
        val sixGb = healthy.copy(physicalTotalBytes = 6_000_000_000L, availBytes = 2_600_000_000L)
        val dSixGb = MemoryPolicy.evaluate(sixGb, ContextProfile.LONG_CONTEXT, userLightMode = false)
        assertTrue(dSixGb.canLoad)
        assertEquals(ContextProfile.HEMAT, dSixGb.recommendedContext)
        assertEquals(MemoryPolicy.IDLE_TIGHT_MS, dSixGb.idleTimeoutMs)

        // Sinyal lowMemory = true menolak pemuatan dengan pesan yang jelas.
        val low = healthy.copy(lowMemory = true)
        val dLow = MemoryPolicy.evaluate(low, ContextProfile.HEMAT, userLightMode = false)
        assertFalse(dLow.canLoad)
        assertNotNull(dLow.shortageReason)

        // GPU butuh margin tambahan; saat availBytes pas-pasan, fallback ke CPU tanpa gagal memuat.
        val gpuTight = healthy.copy(wantGpu = true, availBytes = 720_000_000L)
        val dGpuTight = MemoryPolicy.evaluate(gpuTight, ContextProfile.HEMAT, userLightMode = false)
        assertTrue(dGpuTight.canLoad)
        assertTrue(dGpuTight.preferCpuOverGpu)

        // Background memakai idle timeout singkat.
        val bg = healthy.copy(isBackground = true)
        assertEquals(MemoryPolicy.IDLE_HIDDEN_MS, MemoryPolicy.evaluate(bg, ContextProfile.HEMAT, false).idleTimeoutMs)
        assertTrue(MemoryPolicy.isUrgentTrim(MemoryPolicy.TRIM_RUNNING_CRITICAL))
        assertFalse(MemoryPolicy.isUrgentTrim(MemoryPolicy.TRIM_UI_HIDDEN))
    }

    @Test fun benchmarkStatsComputeMedianAndP95IgnoringFailures() {
        val base = AiBenchmarkMetric(
            timestampMs = 1L,
            taskKey = "summarize",
            backendLabel = "CPU-4",
            cpuThreads = 4,
            mtpActive = true,
            maxNumTokens = 2048,
            maxOutputTokens = 192,
            loadMs = 950.0,
            isColdLoad = false,
            ttftMs = 100.0,
            prefillTokens = 200,
            decodeTokens = 80,
            prefillTokPerSec = 50.0,
            decodeTokPerSec = 13.0,
            totalMs = 100.0,
            peakRssKb = 1_650_000L,
            currentRssKb = 1_500_000L,
            thermalStatus = 0,
            batteryTempTenthsC = 310,
            success = true,
        )
        val metrics = listOf(
            base.copy(totalMs = 1000.0, ttftMs = 200.0, peakRssKb = 1_600_000L),
            base.copy(totalMs = 2000.0, ttftMs = 300.0, peakRssKb = 1_700_000L),
            base.copy(totalMs = 3000.0, ttftMs = 400.0, peakRssKb = 1_650_000L),
            base.copy(totalMs = 99999.0, success = false),
        )
        val s = BenchmarkStats.summarize("summarize", metrics)!!
        assertEquals(3, s.count)
        assertEquals(2000.0, s.medianTotalMs, 0.01)
        assertEquals(3000.0, s.p95TotalMs, 0.01)
        assertEquals(300.0, s.medianTtftMs, 0.01)
        assertEquals(1_700_000L, s.maxPeakRssKb)
        assertNull(BenchmarkStats.summarize("unknown", metrics))
    }

    @Test fun inputSecurityValidatesExternalContentUris() {
        val pkg = "com.catatanku.app"
        assertTrue(InputSecurity.isSafeExternalContentUri("content", "com.android.externalstorage.documents", null, "com.android.externalstorage.documents", pkg))
        assertFalse(InputSecurity.isSafeExternalContentUri("file", "", null, "", pkg))
        assertFalse(InputSecurity.isSafeExternalContentUri("content", pkg, null, pkg, pkg))
        assertFalse(InputSecurity.isSafeExternalContentUri("content", "$pkg.files", null, "$pkg.files", pkg))
        assertFalse(InputSecurity.isSafeExternalContentUri("content", "evil@$pkg", "evil", pkg, pkg))
        assertFalse(InputSecurity.isSafeExternalContentUri("content", "../provider", null, null, pkg))
        assertEquals("baris   satu", InputSecurity.sanitizeLogText("baris\n\r\u0000satu"))
    }
}
