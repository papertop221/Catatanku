package com.catatanku.app.ai.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.catatanku.app.ai.inference.AiText
import com.catatanku.app.ai.modelcatalog.ModelCatalog
import com.catatanku.app.ai.modelcatalog.ModelSpec
import com.catatanku.app.ai.performance.AiBackendKind
import com.catatanku.app.ai.performance.AiBenchmarkMetric
import com.catatanku.app.ai.performance.AiRuntimeProfile
import com.catatanku.app.ai.performance.AiTaskBudget
import com.catatanku.app.ai.performance.AndroidPerfMonitor
import com.catatanku.app.ai.performance.BenchmarkStats
import com.catatanku.app.ai.performance.ContextProfile
import com.catatanku.app.ai.performance.DeviceProfileSelector
import com.catatanku.app.ai.performance.MemoryPolicy
import com.catatanku.app.ai.performance.PerformanceProfileStore
import com.catatanku.app.ai.performance.ProfileSource
import com.catatanku.app.media.Attachments
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface AiState {
    data object NoModel : AiState
    data class Importing(val percent: Int) : AiState
    data object Idle : AiState
    data object Loading : AiState
    data object Ready : AiState
    data class Failed(val message: String) : AiState
}

data class Download(val id: String, val percent: Int, val note: String)

/** Peristiwa non-UI dari pengelola engine AI; diterjemahkan ke pesan pengguna oleh lapisan UI. */
sealed interface AiEvent {
    data object GpuFallbackToCpu : AiEvent
    data object VisionGpuFallbackToCpu : AiEvent
    data class ModelInstalled(val name: String) : AiEvent
    data class ModelVerifyFailed(val name: String) : AiEvent
    data class DownloadFailed(val name: String, val reason: Int) : AiEvent
    data class Message(val text: String) : AiEvent
}

/**
 * Pengelola LLM lokal lewat LiteRT-LM (satu Engine per proses).
 * Tidak menangani UI secara langsung; keputusan performa dan memori mengikuti [DeviceProfileSelector]
 * dan [MemoryPolicy] berbasis metrik perangkat nyata.
 */
@OptIn(ExperimentalApi::class)
class LocalAi private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: LocalAi? = null
        fun get(context: Context): LocalAi = instance ?: synchronized(this) { instance ?: LocalAi(context.applicationContext).also { instance = it } }

        const val MAX_METRICS_HISTORY = 40
    }

    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val monitor = AndroidPerfMonitor(context)
    private val profileStore = PerformanceProfileStore(context)
    private val importFile = File(context.filesDir, "models/model.litertlm")
    private val dlDir: File? get() = context.getExternalFilesDir("models")
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedProfile: AiRuntimeProfile? = null
    @Volatile private var hasVision = false
    @Volatile private var hasAudio = false
    @Volatile private var hasWarmCache = false
    private var watcher: Job? = null
    private var idle: Job? = null
    @Volatile private var lastUse = 0L
    @Volatile private var hidden = false
    @Volatile private var lastTrimLevel = 0
    @Volatile private var activeIdleTimeoutMs = MemoryPolicy.IDLE_FOREGROUND_COMFORT_MS

    private val _state = MutableStateFlow<AiState>(AiState.NoModel)
    val state: StateFlow<AiState> = _state
    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    private val _metrics = MutableStateFlow<List<AiBenchmarkMetric>>(emptyList())
    val metrics: StateFlow<List<AiBenchmarkMetric>> = _metrics
    private val _activeProfile = MutableStateFlow<AiRuntimeProfile?>(null)
    val activeProfile: StateFlow<AiRuntimeProfile?> = _activeProfile
    private val _benchmarkRunning = MutableStateFlow(false)
    val benchmarkRunning: StateFlow<Boolean> = _benchmarkRunning

    val events = MutableSharedFlow<AiEvent>(extraBufferCapacity = 8)

    val deviceRamGb: Int by lazy {
        monitor.memorySnapshot(0, false, wantVision = false, wantAudio = false, wantGpu = false, modelEstimatedBytes = 0L).physicalRamGb
    }

    var backendOverride: AiBackendKind?
        get() = prefs.getString("backend_override", null)?.let { runCatching { AiBackendKind.valueOf(it) }.getOrNull() }
        set(v) {
            if (v == null) prefs.edit().remove("backend_override").apply()
            else prefs.edit().putString("backend_override", v.name).apply()
        }

    var useGpu: Boolean
        get() = currentProfile().backend == AiBackendKind.GPU
        set(v) { backendOverride = if (v) AiBackendKind.GPU else AiBackendKind.CPU }

    var contextProfileOverride: ContextProfile?
        get() = prefs.getString("context_profile", null)?.let { ContextProfile.byId(it) }
        set(v) {
            if (v == null) prefs.edit().remove("context_profile").apply()
            else prefs.edit().putString("context_profile", v.id).apply()
        }

    val lightMode: Boolean get() = prefs.getBoolean("light", deviceRamGb in 1 until ModelCatalog.LIGHT_BELOW_GB)

    suspend fun setLight(on: Boolean) {
        unload()
        prefs.edit().putBoolean("light", on).apply()
        applyActive()
    }

    suspend fun setBackend(override: AiBackendKind?) {
        unload()
        backendOverride = override
        applyActive()
    }

    suspend fun setContextProfile(profile: ContextProfile?) {
        unload()
        contextProfileOverride = profile
        applyActive()
    }

    var activeId: String?
        get() = prefs.getString("active", null)
        private set(v) { prefs.edit().putString("active", v).apply() }

    fun fileFor(id: String?): File? = when (id) {
        null -> null
        ModelCatalog.IMPORT_ID -> importFile
        else -> ModelCatalog.byId(id)?.let { s -> dlDir?.let { File(it, s.fileName) } }
    }

    fun isInstalled(id: String) = fileFor(id)?.exists() == true
    val installedIds: List<String>
        get() = (ModelCatalog.all.map { it.id } + ModelCatalog.IMPORT_ID).filter(::isInstalled)
    private val modelFile: File? get() = fileFor(activeId)?.takeIf { it.exists() }
    val hasModel get() = modelFile != null
    val modelSizeMb get() = (modelFile?.length() ?: 0) / (1024 * 1024)
    val activeSpec: ModelSpec? get() = ModelCatalog.byId(activeId)
    val activeSupportsMultimodal: Boolean get() = activeSpec?.let { it.supportsVision || it.supportsAudio } ?: true
    val activeName: String
        get() = activeSpec?.name ?: if (activeId == ModelCatalog.IMPORT_ID) "Model impor" else "-"

    init {
        if (modelFile == null) activeId = installedIds.firstOrNull()
        applyActive()
        val pending = prefs.getLong("dl_id", -1)
        ModelCatalog.byId(prefs.getString("dl_spec", null))?.let { if (pending >= 0) watch(pending, it) }
    }

    fun currentProfile(): AiRuntimeProfile {
        val id = activeId ?: ModelCatalog.all.first().id
        val fp = monitor.fingerprint(id)
        val cached = profileStore.load(fp)
        val supportsMtp = activeSpec?.let { !it.thinks } ?: true
        return DeviceProfileSelector.select(
            fingerprint = fp,
            cachedProfile = cached,
            userBackendOverride = backendOverride,
            userContextOverride = contextProfileOverride,
            userLightMode = lightMode,
            supportsMtpModel = supportsMtp,
        )
    }

    private fun applyActive() {
        _state.value = if (hasModel) AiState.Idle else AiState.NoModel
        val profile = currentProfile()
        _activeProfile.value = profile
        val modelCap = activeSpec?.inputChars ?: if ((modelFile?.length() ?: 0) > 1_800_000_000L) 6000 else 2800
        val contextCap = profile.contextProfile.maxInputChars
        AiText.maxInput = minOf(modelCap, contextCap)
        _revision.value++
    }

    suspend fun activate(id: String) {
        if (!isInstalled(id)) return
        unload(); activeId = id; applyActive()
    }

    suspend fun delete(id: String) {
        if (id == activeId) unload()
        withContext(Dispatchers.IO) {
            fileFor(id)?.delete()
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.name.contains("litert", ignoreCase = true) || f.name.endsWith(".xnnpack_cache") || f.name.endsWith(".bin")) f.delete()
            }
        }
        if (id == activeId) activeId = installedIds.firstOrNull()
        applyActive()
    }

    // ============================== Unduh ==============================

    fun freeSpaceFor(spec: ModelSpec): Boolean = (dlDir?.usableSpace ?: 0) > spec.bytes + 300L * 1024 * 1024

    fun startDownload(spec: ModelSpec, wifiOnly: Boolean) {
        if (_download.value != null) return
        val dir = dlDir
        if (dir == null) { events.tryEmit(AiEvent.Message("Penyimpanan tidak tersedia.")); return }
        if (!freeSpaceFor(spec)) { events.tryEmit(AiEvent.Message("Ruang penyimpanan tidak cukup untuk ${spec.sizeLabel}.")); return }
        File(dir, spec.fileName + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("${spec.name} (${spec.sizeLabel})")
            .setDescription("Model AI untuk Catatanku")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "models", spec.fileName + ".part")
        val id = try { dm.enqueue(req) } catch (e: Exception) { events.tryEmit(AiEvent.Message("Unduhan tidak bisa dimulai: ${e.message}")); return }
        prefs.edit().putLong("dl_id", id).putString("dl_spec", spec.id).apply()
        watch(id, spec)
    }

    fun cancelDownload() {
        val id = prefs.getLong("dl_id", -1)
        watcher?.cancel()
        if (id >= 0) runCatching { dm.remove(id) }
        clearDownload()
    }

    private fun clearDownload() {
        prefs.edit().remove("dl_id").remove("dl_spec").apply()
        _download.value = null
    }

    private fun watch(dlId: Long, spec: ModelSpec) {
        _download.value = Download(spec.id, 0, "Memulai…")
        watcher = scope.launch {
            val part = File(dlDir, spec.fileName + ".part")
            while (isActive) {
                val row = dm.query(DownloadManager.Query().setFilterById(dlId))?.use { c ->
                    if (!c.moveToFirst()) null else Triple(
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    )
                }
                if (row == null) { clearDownload(); return@launch }
                val (status, done, reason) = row
                val pct = (done * 100 / spec.bytes).toInt().coerceIn(0, 100)
                val mb = "${done / 1_000_000} / ${spec.bytes / 1_000_000} MB"
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { finish(dlId, spec, part); return@launch }
                    DownloadManager.STATUS_FAILED -> {
                        runCatching { dm.remove(dlId) }; clearDownload()
                        events.tryEmit(AiEvent.DownloadFailed(spec.name, reason))
                        return@launch
                    }
                    DownloadManager.STATUS_PAUSED -> _download.value = Download(spec.id, pct, when (reason) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Menunggu Wi-Fi · $mb"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Menunggu jaringan · $mb"
                        else -> "Dijeda, akan dilanjutkan otomatis · $mb"
                    })
                    DownloadManager.STATUS_PENDING -> _download.value = Download(spec.id, pct, "Menunggu antrean…")
                    else -> _download.value = Download(spec.id, pct, mb)
                }
                delay(800)
            }
        }
    }

    private suspend fun finish(dlId: Long, spec: ModelSpec, part: File) {
        _download.value = Download(spec.id, 100, "Memeriksa keutuhan berkas…")
        val ok = part.length() == spec.bytes && sha256(part) == spec.sha256
        val target = fileFor(spec.id)
        if (!ok || target == null) {
            runCatching { dm.remove(dlId) }; part.delete(); clearDownload()
            events.tryEmit(AiEvent.ModelVerifyFailed(spec.name))
            return
        }
        target.delete()
        val moved = part.renameTo(target)
        prefs.edit().remove("dl_id").remove("dl_spec").apply()
        runCatching { dm.remove(dlId) }
        _download.value = null
        if (!moved) { events.tryEmit(AiEvent.Message("Gagal menyimpan ${spec.name}.")); return }
        activate(spec.id)
        events.tryEmit(AiEvent.ModelInstalled(spec.name))
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ============================== Impor berkas ==============================

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        if (!Attachments.isSafeExternalUri(context, uri)) error("Sumber berkas model tidak diizinkan.")
        if (activeId == ModelCatalog.IMPORT_ID) unload()
        val tmp = File(importFile.parentFile!!.apply { mkdirs() }, "import.tmp")
        val before = _state.value
        try {
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val need = if (total > 0) total else 700L * 1024 * 1024
            if (context.filesDir.usableSpace < need + 50L * 1024 * 1024)
                error("Ruang penyimpanan tidak cukup (butuh ±${need / (1024 * 1024)} MB).")
            _state.value = AiState.Importing(0)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = AiState.Importing((done * 100 / total).toInt())
                    }
                }
            }
            if (tmp.length() < 10L * 1024 * 1024) error("Berkas terlalu kecil untuk sebuah model LLM.")
            importFile.delete()
            if (!tmp.renameTo(importFile)) error("Gagal menyimpan model.")
            unload(); activeId = ModelCatalog.IMPORT_ID; applyActive()
        } catch (e: Exception) {
            tmp.delete()
            _state.value = if (before is AiState.Importing) AiState.NoModel else before
            throw e
        }
    }

    // ============================== Menjalankan ==============================

    suspend fun unload() = lock.withLock { release() }

    private suspend fun release() {
        withContext(Dispatchers.IO) { runCatching { engine?.close() } }
        engine = null; loadedProfile = null; hasVision = false; hasAudio = false
        if (_state.value !is AiState.Importing) _state.value = if (hasModel) AiState.Idle else AiState.NoModel
    }

    private fun touch() {
        lastUse = System.currentTimeMillis()
        idle?.cancel()
        val wait = activeIdleTimeoutMs
        idle = scope.launch {
            delay(wait)
            lock.withLock { if (engine != null && System.currentTimeMillis() - lastUse >= wait) release() }
        }
    }

    fun onForeground() {
        if (!hidden) return
        hidden = false
        lastTrimLevel = 0
        val base = currentProfile()
        val est = activeSpec?.workingBytes(lightMode) ?: ((modelFile?.length() ?: 0L) * 3 / 4)
        val snap = monitor.memorySnapshot(0, isBackground = false, hasVision, hasAudio, base.backend == AiBackendKind.GPU, est)
        activeIdleTimeoutMs = MemoryPolicy.evaluate(snap, base.contextProfile, lightMode).idleTimeoutMs
        if (engine != null) touch()
    }

    fun onTrimMemory(level: Int) {
        lastTrimLevel = level
        if (level >= MemoryPolicy.TRIM_UI_HIDDEN) hidden = true
        if (engine == null) return
        if (MemoryPolicy.isUrgentTrim(level)) {
            scope.launch { if (lock.tryLock()) try { release() } finally { lock.unlock() } }
        } else if (level >= MemoryPolicy.TRIM_UI_HIDDEN) {
            activeIdleTimeoutMs = MemoryPolicy.IDLE_HIDDEN_MS
            touch()
        }
    }

    private fun startEngine(
        file: File,
        profile: AiRuntimeProfile,
        vOn: Boolean,
        aOn: Boolean,
    ): Engine {
        ExperimentalFlags.enableBenchmark = true
        val mainBackend = when (profile.backend) {
            AiBackendKind.GPU -> Backend.GPU()
            AiBackendKind.CPU -> Backend.CPU(threadCount = profile.cpuThreads)
        }
        val visionBackend = if (!vOn) null else when (profile.visionBackend) {
            AiBackendKind.GPU -> Backend.GPU()
            AiBackendKind.CPU -> Backend.CPU(threadCount = profile.cpuThreads)
        }
        // Gemma 4 E2B audio hanya berjalan stabil di CPU.
        val audioBackend = if (aOn) Backend.CPU(threadCount = profile.cpuThreads) else null
        val specDecoding = SpeculativeDecodingConfig(
            if (profile.enableMtpEngine) SpeculativeDecodingMode.ADAPTIVE else SpeculativeDecodingMode.OFF
        )
        return Engine(
            EngineConfig(
                modelPath = file.path,
                backend = mainBackend,
                visionBackend = visionBackend,
                audioBackend = audioBackend,
                maxNumTokens = profile.contextProfile.maxNumTokens,
                maxNumImages = if (vOn) 1 else null,
                cacheDir = context.cacheDir.path,
                speculativeDecodingConfig = specDecoding,
            )
        ).also { it.initialize() }
    }

    private fun ensureLoaded(
        force: Boolean = false,
        wantVision: Boolean = false,
        wantAudio: Boolean = false,
    ): Pair<Engine, Double> {
        val file = checkNotNull(modelFile) { "Model belum dipasang." }
        val baseProfile = currentProfile()
        val estBytes = activeSpec?.workingBytes(lightMode) ?: (file.length() * 3 / 4)
        val snap = monitor.memorySnapshot(
            lastTrimLevel = lastTrimLevel,
            isBackground = hidden,
            wantVision = wantVision || hasVision,
            wantAudio = wantAudio || hasAudio,
            wantGpu = baseProfile.backend == AiBackendKind.GPU,
            modelEstimatedBytes = estBytes,
        )
        val decision = MemoryPolicy.evaluate(snap, baseProfile.contextProfile, lightMode)
        activeIdleTimeoutMs = decision.idleTimeoutMs

        val effectiveProfile = baseProfile.copy(
            backend = if (decision.preferCpuOverGpu) AiBackendKind.CPU else baseProfile.backend,
            contextProfile = decision.recommendedContext,
        )

        engine?.let { cur ->
            val curProf = loadedProfile
            val sameEngineCfg = curProf != null &&
                curProf.backend == effectiveProfile.backend &&
                curProf.contextProfile == effectiveProfile.contextProfile &&
                curProf.enableMtpEngine == effectiveProfile.enableMtpEngine &&
                (!wantVision || (hasVision && curProf.visionBackend == effectiveProfile.visionBackend)) &&
                (!wantAudio || hasAudio)
            if (sameEngineCfg) return cur to 0.0
            runCatching { cur.close() }
            engine = null; loadedProfile = null; hasVision = false; hasAudio = false
        }

        if (!force && !decision.canLoad) {
            val why = decision.shortageReason ?: "RAM tidak mencukupi."
            _state.value = AiState.Failed("$why, atau paksa lewat Pengaturan → Model AI → Uji muat.")
            throw IllegalStateException(why)
        }

        val vOn = wantVision || hasVision
        val aOn = wantAudio || hasAudio
        _state.value = AiState.Loading
        val t0 = System.nanoTime()
        return try {
            var chosen = effectiveProfile
            val loaded = try {
                startEngine(file, chosen, vOn, aOn)
            } catch (firstErr: Throwable) {
                if (chosen.backend == AiBackendKind.GPU) {
                    backendOverride = AiBackendKind.CPU
                    chosen = chosen.copy(backend = AiBackendKind.CPU, visionBackend = AiBackendKind.CPU)
                    events.tryEmit(AiEvent.GpuFallbackToCpu)
                    startEngine(file, chosen, vOn, aOn)
                } else if (vOn && chosen.visionBackend == AiBackendKind.GPU) {
                    chosen = chosen.copy(visionBackend = AiBackendKind.CPU)
                    events.tryEmit(AiEvent.VisionGpuFallbackToCpu)
                    startEngine(file, chosen, vOn, aOn)
                } else {
                    throw firstErr
                }
            }
            val loadMs = (System.nanoTime() - t0) / 1e6
            hasWarmCache = true
            engine = loaded
            loadedProfile = chosen
            hasVision = vOn
            hasAudio = aOn
            _activeProfile.value = chosen
            AiText.maxInput = minOf(activeSpec?.inputChars ?: 6000, chosen.contextProfile.maxInputChars)
            _state.value = AiState.Ready
            loaded to loadMs
        } catch (e: Throwable) {
            if (vOn || aOn) {
                _state.value = if (hasModel) AiState.Idle else AiState.NoModel
                throw IllegalStateException("Model $activeName belum mendukung gambar/suara. Gunakan Gemma 4 E2B untuk membaca gambar atau rekaman suara.")
            }
            _state.value = AiState.Failed("Model gagal dimuat; mungkin RAM tidak cukup: ${e.message}")
            throw e
        }
    }

    suspend fun load(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.Default) { ensureLoaded(force) }
        touch()
    }

    /**
     * Menjalankan satu permintaan teks atau multimodal dengan batas token keluaran per tugas,
     * kebijakan MTP per percakapan, serta turunan resolusi gambar OCR tanpa mengubah berkas asli.
     */
    suspend fun generate(
        prompt: String,
        task: AiTaskBudget = AiTaskBudget.CUSTOM,
        imageFile: File? = null,
        audioFile: File? = null,
        ocrDerivative: Boolean = false,
        onPartial: (String) -> Unit = {},
    ): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val wasWarm = hasWarmCache
            val (e, loadMs) = ensureLoaded(wantVision = imageFile != null, wantAudio = audioFile != null)
            val profile = loadedProfile ?: currentProfile()
            val sampler = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3, seed = 1)
            val useMtpHere = profile.enableMtpEngine && task.useMtp && imageFile == null && audioFile == null
            val convConfig = ConversationConfig(
                samplerConfig = sampler,
                maxOutputToken = task.maxOutputTokens,
                thinkingConfig = if (activeSpec?.thinks == true) ThinkingConfig(false) else null,
                speculativeDecodingConfig = SpeculativeDecodingConfig(
                    if (useMtpHere) SpeculativeDecodingMode.ADAPTIVE else SpeculativeDecodingMode.OFF
                ),
            )
            val c = e.createConversation(convConfig)
            val tempOcrFile = if (imageFile != null && ocrDerivative) {
                Attachments.createOcrDerivative(context, imageFile.name, Attachments.OCR_DERIVATIVE_PX)
            } else null
            val effectiveImage = tempOcrFile ?: imageFile
            val startNs = System.nanoTime()
            var ok = false
            try {
                var acc = ""
                val flow = if (effectiveImage == null && audioFile == null) {
                    c.sendMessageAsync(prompt)
                } else {
                    val parts = buildList<Content> {
                        if (effectiveImage != null) add(Content.ImageFile(effectiveImage.absolutePath))
                        if (audioFile != null) add(Content.AudioFile(audioFile.absolutePath))
                        add(Content.Text(prompt))
                    }
                    c.sendMessageAsync(Contents.of(parts))
                }
                flow.collect { m ->
                    val piece = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    acc = if (acc.isNotEmpty() && piece.length > acc.length && piece.startsWith(acc)) piece else acc + piece
                    onPartial(acc)
                }
                ok = true
                acc
            } catch (t: CancellationException) {
                runCatching { c.cancelProcess() }
                throw t
            } finally {
                val totalMs = (System.nanoTime() - startNs) / 1e6
                val bi = runCatching { c.getBenchmarkInfo() }.getOrNull()
                val (hwm, rss) = monitor.processRssKb()
                val metric = AiBenchmarkMetric(
                    timestampMs = System.currentTimeMillis(),
                    taskKey = task.taskKey,
                    backendLabel = profile.backend.label,
                    cpuThreads = profile.cpuThreads,
                    mtpActive = useMtpHere,
                    maxNumTokens = profile.contextProfile.maxNumTokens,
                    maxOutputTokens = task.maxOutputTokens,
                    loadMs = loadMs,
                    isColdLoad = loadMs > 0.0 && !wasWarm,
                    ttftMs = (bi?.timeToFirstTokenInSecond ?: 0.0) * 1000.0,
                    prefillTokens = bi?.lastPrefillTokenCount ?: 0,
                    decodeTokens = bi?.lastDecodeTokenCount ?: 0,
                    prefillTokPerSec = bi?.lastPrefillTokensPerSecond ?: 0.0,
                    decodeTokPerSec = bi?.lastDecodeTokensPerSecond ?: 0.0,
                    totalMs = totalMs,
                    peakRssKb = hwm,
                    currentRssKb = rss,
                    thermalStatus = monitor.thermalStatus(),
                    batteryTempTenthsC = monitor.batteryTempTenthsC(),
                    success = ok,
                )
                _metrics.value = (_metrics.value + metric).takeLast(MAX_METRICS_HISTORY)
                tempOcrFile?.delete()
                runCatching { c.close() }
                touch()
            }
        }
    }

    /**
     * Benchmark singkat dan terulang langsung di perangkat pengguna untuk membandingkan CPU-4 vs GPU
     * (beserta MTP) lalu menyimpan profil tercepat yang stabil untuk SoC & model ini.
     */
    suspend fun runDeviceBenchmark(): String = lock.withLock {
        val file = checkNotNull(modelFile) { "Pasang model AI lebih dulu untuk menjalankan benchmark." }
        val id = activeId ?: ModelCatalog.all.first().id
        val fp = monitor.fingerprint(id)
        _benchmarkRunning.value = true
        return@withLock try {
            withContext(Dispatchers.Default) {
                release()
                val prompt = AiText.prompt(
                    com.catatanku.app.domain.model.AiAction.Summarize,
                    "Rapat tim Senin",
                    "Bahas peluncuran aplikasi kasir bulan depan. Rina siapkan materi promosi sebelum Kamis, " +
                        "Dodi hubungi vendor printer struk, dan tim teknis selesaikan bug login sebelum Jumat. " +
                        "Anggaran iklan disetujui 5 juta rupiah.",
                )

                suspend fun measureCandidate(backendKind: AiBackendKind, mtp: Boolean): Pair<Double, Long>? {
                    var eng: Engine? = null
                    return try {
                        val prof = AiRuntimeProfile(
                            backend = backendKind,
                            cpuThreads = 4,
                            visionBackend = backendKind,
                            audioBackend = AiBackendKind.CPU,
                            contextProfile = ContextProfile.HEMAT,
                            enableMtpEngine = mtp,
                            source = ProfileSource.DEVICE_CACHE,
                            fingerprint = fp,
                        )
                        eng = startEngine(file, prof, vOn = false, aOn = false)
                        // 1 pemanasan + 2 pengukuran
                        val times = ArrayList<Double>()
                        repeat(3) { iter ->
                            val conv = eng.createConversation(
                                ConversationConfig(
                                    samplerConfig = SamplerConfig(20, 0.9, 0.3, 1),
                                    maxOutputToken = AiTaskBudget.SUMMARIZE.maxOutputTokens,
                                    speculativeDecodingConfig = SpeculativeDecodingConfig(
                                        if (mtp) SpeculativeDecodingMode.ADAPTIVE else SpeculativeDecodingMode.OFF
                                    ),
                                )
                            )
                            val a = System.nanoTime()
                            conv.sendMessage(prompt)
                            val ms = (System.nanoTime() - a) / 1e6
                            if (iter > 0) times += ms
                            conv.close()
                        }
                        val (hwm, _) = monitor.processRssKb()
                        BenchmarkStats.percentile(times, 0.5) to hwm
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching { eng?.close() }
                    }
                }

                val cpuRes = measureCandidate(AiBackendKind.CPU, mtp = true)
                val gpuRes = measureCandidate(AiBackendKind.GPU, mtp = true)
                val winner = when {
                    cpuRes != null && gpuRes != null ->
                        if (gpuRes.first < cpuRes.first * 0.88 && gpuRes.second <= cpuRes.second + 700_000L)
                            AiBackendKind.GPU else AiBackendKind.CPU
                    gpuRes != null -> AiBackendKind.GPU
                    else -> AiBackendKind.CPU
                }
                val chosen = AiRuntimeProfile(
                    backend = winner,
                    cpuThreads = 4,
                    visionBackend = if (gpuRes != null) AiBackendKind.GPU else AiBackendKind.CPU,
                    audioBackend = AiBackendKind.CPU,
                    contextProfile = ContextProfile.HEMAT,
                    enableMtpEngine = true,
                    source = ProfileSource.DEVICE_CACHE,
                    fingerprint = fp,
                )
                profileStore.save(chosen)
                backendOverride = null
                applyActive()
                buildString {
                    append("Benchmark selesai (${fp.soc}): ")
                    cpuRes?.let { append("CPU-4+MTP %.1f dtk (%.0f MB)".format(it.first / 1000.0, it.second / 1024.0)) }
                    gpuRes?.let { append(" · GPU+MTP %.1f dtk (%.0f MB)".format(it.first / 1000.0, it.second / 1024.0)) }
                    append(" → Dipilih ${winner.label}.")
                }
            }
        } finally {
            _benchmarkRunning.value = false
        }
    }

    suspend fun reminderPhrase(title: String, body: String): String? =
        AiText.parseReminder(generate(AiText.reminderPrompt(title, body), AiTaskBudget.REMINDER))

    suspend fun suggestTags(title: String, body: String, existing: List<String>): List<String> =
        com.catatanku.app.domain.usecase.TextTools.parseList(
            AiText.strip(generate(AiText.tagsPrompt(title, body, existing), AiTaskBudget.TAG)), 5
        ).map(com.catatanku.app.domain.usecase.TextTools::normalizeTag)

    suspend fun expandQuery(query: String): List<String> =
        com.catatanku.app.domain.usecase.TextTools.parseList(
            AiText.strip(generate(AiText.expandPrompt(query), AiTaskBudget.EXPAND_SEARCH)), 8
        )

    suspend fun organize(title: String, body: String, folders: List<String>, tags: List<String>): AiText.Organized {
        val o = AiText.parseOrganize(
            AiText.strip(generate(AiText.organizePrompt(title, body, folders, tags), AiTaskBudget.ORGANIZE)), folders
        )
        return if (o.tags.isEmpty()) o.copy(tags = suggestTags(title.ifBlank { o.title }, body, tags)) else o
    }
}
