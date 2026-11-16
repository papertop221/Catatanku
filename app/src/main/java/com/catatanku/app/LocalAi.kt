package com.catatanku.app

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
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
import kotlin.math.ceil

sealed interface AiState {
    data object NoModel : AiState
    data class Importing(val percent: Int) : AiState
    data object Idle : AiState                 // file model ada, belum dimuat ke RAM
    data object Loading : AiState
    data object Ready : AiState
    data class Failed(val message: String) : AiState
}

data class Download(val id: String, val percent: Int, val note: String)

/**
 * LLM lokal lewat LiteRT-LM. Model (.litertlm) diunduh dari katalog atau diimpor dari berkas,
 * lalu dijalankan sepenuhnya di perangkat.
 *
 * Satu-satunya pemakaian jaringan di aplikasi ini ada di [startDownload], dan itu dijalankan oleh
 * DownloadManager milik Android; kode aplikasi sendiri tidak pernah membuka koneksi.
 */
class LocalAi private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: LocalAi? = null
        /** Satu mesin untuk seluruh proses: layar utama dan menu teks terpilih memakai model yang sama, tidak pernah dua salinan di RAM. */
        fun get(context: Context): LocalAi = instance ?: synchronized(this) { instance ?: LocalAi(context.applicationContext).also { instance = it } }

        const val IDLE_MS = 3 * 60_000L
        // Mode ringan: jendela konteks lebih kecil (RAM lebih hemat), masukan lebih pendek, model segera dilepas.
        const val LIGHT_IDLE_MS = 20_000L
        const val LIGHT_TOKENS = 2048
        const val LIGHT_INPUT = 2400
        // Aplikasi sudah ditinggalkan: model tidak perlu menahan RAM selama waktu tunggu biasa.
        const val HIDDEN_IDLE_MS = 20_000L
        // Model tidak dimuat bila RAM yang tersedia di bawah bagian ini dari kebutuhannya (perkiraan; belum diukur di HP).
        const val MIN_FREE_PERCENT = 35
    }
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val importFile = File(context.filesDir, "models/model.litertlm")
    private val dlDir: File? get() = context.getExternalFilesDir("models")
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var hasVision = false
    @Volatile private var hasAudio = false
    private var watcher: Job? = null
    private var idle: Job? = null
    @Volatile private var lastUse = 0L
    @Volatile private var hidden = false      // aplikasi sedang tidak tampil

    private val _state = MutableStateFlow<AiState>(AiState.NoModel)
    val state: StateFlow<AiState> = _state
    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download
    /** Bertambah tiap daftar model terpasang berubah, agar UI menggambar ulang. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    val events = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val deviceRamGb: Int by lazy {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        ceil(mi.totalMem / 1073741824.0).toInt()
    }

    var useGpu: Boolean
        get() = prefs.getBoolean("gpu", false)
        set(v) { prefs.edit().putBoolean("gpu", v).apply() }

    /** Pilihan hemat untuk HP dengan RAM terbatas; berlaku untuk model mana pun. Ubah lewat [setLight]. */
    val lightMode: Boolean get() = prefs.getBoolean("light", deviceRamGb in 1 until ModelCatalog.LIGHT_BELOW_GB)
    private val idleMs get() = if (hidden) minOf(HIDDEN_IDLE_MS, if (lightMode) LIGHT_IDLE_MS else IDLE_MS) else if (lightMode) LIGHT_IDLE_MS else IDLE_MS

    suspend fun setLight(on: Boolean) {
        unload()
        prefs.edit().putBoolean("light", on).apply()
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
    val activeName: String
        get() = ModelCatalog.byId(activeId)?.name ?: if (activeId == ModelCatalog.IMPORT_ID) "Model impor" else "-"

    init {
        if (modelFile == null) activeId = installedIds.firstOrNull()     // termasuk peningkatan dari v1.x
        applyActive()
        // Lanjutkan memantau unduhan yang masih berjalan saat aplikasi sebelumnya ditutup.
        val pending = prefs.getLong("dl_id", -1)
        ModelCatalog.byId(prefs.getString("dl_spec", null))?.let { if (pending >= 0) watch(pending, it) }
    }

    private fun applyActive() {
        _state.value = if (hasModel) AiState.Idle else AiState.NoModel
        // Model besar punya jendela konteks lebih lebar, jadi boleh membaca catatan lebih panjang.
        val full = ModelCatalog.byId(activeId)?.inputChars ?: if ((modelFile?.length() ?: 0) > 1_800_000_000L) 6000 else 2800
        AiText.maxInput = if (lightMode) minOf(full, LIGHT_INPUT) else full
        _revision.value++
    }

    suspend fun activate(id: String) {
        if (!isInstalled(id)) return
        unload(); activeId = id; applyActive()
    }

    suspend fun delete(id: String) {
        if (id == activeId) unload()
        withContext(Dispatchers.IO) { fileFor(id)?.delete() }
        if (id == activeId) activeId = installedIds.firstOrNull()
        applyActive()
    }

    // ============================== Unduh ==============================

    fun freeSpaceFor(spec: ModelSpec): Boolean = (dlDir?.usableSpace ?: 0) > spec.bytes + 300L * 1024 * 1024

    fun startDownload(spec: ModelSpec, wifiOnly: Boolean) {
        if (_download.value != null) return
        val dir = dlDir
        if (dir == null) { events.tryEmit("Penyimpanan tidak tersedia."); return }
        if (!freeSpaceFor(spec)) { events.tryEmit("Ruang penyimpanan tidak cukup untuk ${spec.sizeLabel}."); return }
        File(dir, spec.fileName + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("${spec.name} (${spec.sizeLabel})")
            .setDescription("Model AI untuk Catatanku")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "models", spec.fileName + ".part")
        val id = try { dm.enqueue(req) } catch (e: Exception) { events.tryEmit("Unduhan tidak bisa dimulai: ${e.message}"); return }
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
                if (row == null) { clearDownload(); return@launch }          // dibatalkan dari notifikasi
                val (status, done, reason) = row
                val pct = (done * 100 / spec.bytes).toInt().coerceIn(0, 100)
                val mb = "${done / 1_000_000} / ${spec.bytes / 1_000_000} MB"
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { finish(dlId, spec, part); return@launch }
                    DownloadManager.STATUS_FAILED -> {
                        runCatching { dm.remove(dlId) }; clearDownload()
                        events.tryEmit("Unduhan ${spec.name} gagal (kode $reason). Periksa koneksi lalu coba lagi.")
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

    /** Verifikasi ukuran dan SHA-256 sebelum model dipakai, lalu jadikan model aktif. */
    private suspend fun finish(dlId: Long, spec: ModelSpec, part: File) {
        _download.value = Download(spec.id, 100, "Memeriksa keutuhan berkas…")
        val ok = part.length() == spec.bytes && sha256(part) == spec.sha256
        val target = fileFor(spec.id)
        if (!ok || target == null) {
            runCatching { dm.remove(dlId) }; part.delete(); clearDownload()
            events.tryEmit("Berkas ${spec.name} rusak atau tidak lengkap; sudah dihapus. Silakan unduh ulang.")
            return
        }
        target.delete()
        val moved = part.renameTo(target)
        // Lepas catatan unduhan tanpa menghapus berkas yang sudah dipindah.
        prefs.edit().remove("dl_id").remove("dl_spec").apply()
        runCatching { dm.remove(dlId) }
        _download.value = null
        if (!moved) { events.tryEmit("Gagal menyimpan ${spec.name}."); return }
        activate(spec.id)
        events.tryEmit("${spec.name} terpasang dan aktif.")
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

    /** Melepas model dari RAM (file tetap ada). */
    suspend fun unload() = lock.withLock { release() }

    private suspend fun release() {
        withContext(Dispatchers.IO) { runCatching { engine?.close() } }
        engine = null; hasVision = false; hasAudio = false
        if (_state.value !is AiState.Importing) _state.value = if (hasModel) AiState.Idle else AiState.NoModel
    }

    /**
     * Model memakai RAM ratusan MB sampai beberapa GB. Setelah beberapa menit tanpa permintaan, model dilepas
     * sendiri supaya HP tidak terbebani; permintaan berikutnya memuatnya lagi.
     */
    private fun touch() {
        lastUse = System.currentTimeMillis()
        idle?.cancel()
        idle = scope.launch {
            val wait = idleMs
            delay(wait)
            lock.withLock { if (engine != null && System.currentTimeMillis() - lastUse >= wait) release() }
        }
    }

    /** Aplikasi tampil lagi: waktu tunggu kembali normal. */
    fun onForeground() {
        if (!hidden) return
        hidden = false
        if (engine != null) touch()
    }

    /**
     * Sinyal memori dari sistem. Model adalah pemakai RAM terbesar aplikasi ini, jadi ia yang dilepas lebih dulu:
     * segera bila sistem kekurangan memori atau aplikasi sudah lama di latar, dan lebih cepat dari biasanya begitu
     * aplikasi ditinggalkan. Permintaan yang sedang berjalan tidak diputus.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) hidden = true
        if (engine == null) return
        val urgent = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (urgent) scope.launch { if (lock.tryLock()) try { release() } finally { lock.unlock() } }
        else if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) touch()      // hitung ulang dengan waktu tunggu pendek
    }

    /**
     * Alasan model sebaiknya tidak dimuat sekarang, atau null bila memori cukup. Memuat model saat RAM nyaris habis
     * membuat HP macet lalu aplikasi dimatikan sistem; lebih baik ditolak dengan pesan yang jelas.
     */
    private fun memoryShortage(file: File): String? {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        if (mi.totalMem <= 0) return null       // tidak ada data (mis. lingkungan uji)
        val need = ModelCatalog.byId(activeId)?.workingBytes(lightMode) ?: (file.length() * 3 / 4)
        if (!mi.lowMemory && mi.availMem >= need * MIN_FREE_PERCENT / 100) return null
        return "RAM HP sedang penuh (tersedia ±%.1f GB, model butuh ±%.1f GB). Tutup aplikasi lain lalu coba lagi"
            .format(mi.availMem / 1e9, need / 1e9)
    }

    private fun ensureLoaded(force: Boolean = false, wantVision: Boolean = false, wantAudio: Boolean = false): Engine {
        engine?.let {
            if ((!wantVision || hasVision) && (!wantAudio || hasAudio)) return it
            runCatching { it.close() }
            engine = null; hasVision = false; hasAudio = false
        }
        val file = checkNotNull(modelFile) { "Model belum dipasang." }
        if (!force) memoryShortage(file)?.let { why ->
            _state.value = AiState.Failed("$why, atau paksa lewat Pengaturan → Model AI → Uji muat.")
            throw IllegalStateException(why)
        }
        _state.value = AiState.Loading
        val vOn = wantVision || hasVision
        val aOn = wantAudio || hasAudio
        return try {
            Engine(
                EngineConfig(
                    modelPath = file.path,
                    backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                    visionBackend = if (vOn) Backend.CPU() else null,
                    audioBackend = if (aOn) Backend.CPU() else null,
                    maxNumTokens = if (lightMode) LIGHT_TOKENS else null,
                    maxNumImages = if (vOn) 1 else null,
                    cacheDir = context.cacheDir.path,
                )
            ).also { it.initialize(); engine = it; hasVision = vOn; hasAudio = aOn; _state.value = AiState.Ready }
        } catch (e: Throwable) {
            if (vOn || aOn) {
                _state.value = if (hasModel) AiState.Idle else AiState.NoModel
                throw IllegalStateException("Model $activeName belum mendukung gambar/suara. Gunakan Gemma 4 E2B untuk membaca gambar atau rekaman suara.")
            }
            _state.value = AiState.Failed(
                "Model gagal dimuat" + (if (useGpu) " dengan GPU; coba matikan GPU" else "; mungkin RAM tidak cukup") + ": ${e.message}"
            )
            throw e
        }
    }

    /** Memuat model sekarang juga. [force] melewati pemeriksaan RAM (tombol "Uji muat" di Pengaturan). */
    suspend fun load(force: Boolean = false) = lock.withLock { withContext(Dispatchers.Default) { ensureLoaded(force) }; touch() }

    /**
     * Menjalankan satu permintaan teks atau multimodal (gambar/suara). [onPartial] menerima teks yang terkumpul sejauh ini.
     * Membatalkan coroutine pemanggil akan menghentikan pembangkitan.
     */
    suspend fun generate(prompt: String, imageFile: File? = null, audioFile: File? = null, onPartial: (String) -> Unit = {}): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val e = ensureLoaded(wantVision = imageFile != null, wantAudio = audioFile != null)
            // Percakapan baru tiap permintaan: tanpa riwayat, konteks kecil, hasil konsisten.
            val sampler = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3, seed = 1)
            // Model yang "berpikir" dulu terlalu lambat di HP; penalarannya dimatikan.
            val config = if (ModelCatalog.byId(activeId)?.thinks == true)
                ConversationConfig(samplerConfig = sampler, thinkingConfig = ThinkingConfig(false))
            else ConversationConfig(samplerConfig = sampler)
            val c = e.createConversation(config)
            try {
                var acc = ""
                val flow = if (imageFile == null && audioFile == null) {
                    c.sendMessageAsync(prompt)
                } else {
                    val parts = buildList<Content> {
                        if (imageFile != null) add(Content.ImageFile(imageFile.absolutePath))
                        if (audioFile != null) add(Content.AudioFile(audioFile.absolutePath))
                        add(Content.Text(prompt))
                    }
                    c.sendMessageAsync(Contents.of(parts))
                }
                flow.collect { m ->
                    val piece = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    // Tahan terhadap dua gaya streaming: potongan baru, atau teks kumulatif.
                    acc = if (acc.isNotEmpty() && piece.length > acc.length && piece.startsWith(acc)) piece else acc + piece
                    onPartial(acc)
                }
                acc
            } catch (t: CancellationException) {
                runCatching { c.cancelProcess() }
                throw t
            } finally {
                runCatching { c.close() }
                touch()
            }
        }
    }

    /** Frasa waktu yang layak diingatkan dari sebuah catatan, atau null bila tidak ada. */
    suspend fun reminderPhrase(title: String, body: String): String? =
        AiText.parseReminder(generate(AiText.reminderPrompt(title, body)))

    suspend fun suggestTags(title: String, body: String, existing: List<String>): List<String> =
        TextTools.parseList(AiText.strip(generate(AiText.tagsPrompt(title, body, existing))), 5).map(TextTools::normalizeTag)

    suspend fun expandQuery(query: String): List<String> =
        TextTools.parseList(AiText.strip(generate(AiText.expandPrompt(query))), 8)

    suspend fun organize(title: String, body: String, folders: List<String>, tags: List<String>): AiText.Organized {
        val o = AiText.parseOrganize(AiText.strip(generate(AiText.organizePrompt(title, body, folders, tags))), folders)
        // Model kecil kadang hanya mengisi judul; minta tag secara terpisah, yang lebih andal.
        return if (o.tags.isEmpty()) o.copy(tags = suggestTags(title.ifBlank { o.title }, body, tags)) else o
    }
}
