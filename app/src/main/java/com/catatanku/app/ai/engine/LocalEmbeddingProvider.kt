package com.catatanku.app.ai.engine

import android.content.Context
import android.net.Uri
import com.catatanku.app.ai.performance.AndroidPerfMonitor
import com.catatanku.app.ai.performance.ContextProfile
import com.catatanku.app.ai.performance.MemoryPolicy
import com.catatanku.app.domain.usecase.EmbeddingCodec
import com.catatanku.app.domain.usecase.EmbeddingProvider
import com.catatanku.app.media.Attachments
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Penyedia embedding lokal opsional menggunakan LiteRT-LM [EmbeddingEngine] untuk pencarian semantik.
 * Bila model embedding belum dipasang atau RAM terbatas, [isAvailable] bernilai false dan aplikasi
 * jatuh kembali secara murni ke pencarian TF-IDF + trigram.
 */
class LocalEmbeddingProvider private constructor(private val context: Context) : EmbeddingProvider {
    companion object {
        @Volatile private var instance: LocalEmbeddingProvider? = null
        fun get(context: Context): LocalEmbeddingProvider =
            instance ?: synchronized(this) { instance ?: LocalEmbeddingProvider(context.applicationContext).also { instance = it } }
    }

    private val monitor = AndroidPerfMonitor(context)
    private val modelFile = File(context.filesDir, "models/embedding.litertlm")
    private val lock = Mutex()
    @Volatile private var engine: EmbeddingEngine? = null

    private val _installed = MutableStateFlow(modelFile.isFile)
    val installed: StateFlow<Boolean> = _installed

    override val isAvailable: Boolean
        get() = modelFile.isFile

    override val modelId: String
        get() = if (modelFile.isFile) "emb_${modelFile.length()}" else ""

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        if (!Attachments.isSafeExternalUri(context, uri)) error("Sumber model embedding tidak diizinkan.")
        release()
        val tmp = File(modelFile.parentFile!!.apply { mkdirs() }, "embedding_import.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out, 64 * 1024) }
            } ?: error("Berkas tidak bisa dibuka.")
            if (tmp.length() < 1024 * 1024) error("Berkas terlalu kecil untuk model embedding.")
            val info = ModelInfo.from(tmp.absolutePath)
            val type = try { info.modelType } finally { info.close() }
            if (type != ModelType.EMBEDDING) error("Berkas ini bukan model embedding LiteRT-LM.")
            modelFile.delete()
            if (!tmp.renameTo(modelFile)) error("Gagal menyimpan model embedding.")
            _installed.value = true
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        release()
        modelFile.delete()
        _installed.value = false
    }

    suspend fun release() = lock.withLock {
        withContext(Dispatchers.IO) { runCatching { engine?.close() } }
        engine = null
    }

    private fun ensureEngine(): EmbeddingEngine? {
        if (!modelFile.isFile) return null
        engine?.let { return it }
        val snap = monitor.memorySnapshot(
            lastTrimLevel = 0,
            isBackground = false,
            wantVision = false,
            wantAudio = false,
            wantGpu = false,
            modelEstimatedBytes = maxOf(modelFile.length(), 150_000_000L),
        )
        if (!MemoryPolicy.evaluate(snap, ContextProfile.HEMAT, userLightMode = false).canLoad) return null
        val created = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(threadCount = 2),
                cacheDir = context.cacheDir.absolutePath,
                maxInputLength = 512,
            )
        ).also { it.initialize() }
        engine = created
        return created
    }

    override suspend fun embedDocument(title: String, body: String, tags: List<String>): FloatArray? =
        embedText(EmbeddingCodec.documentText(title, body, tags))

    override suspend fun embedQuery(query: String): FloatArray? =
        embedText(query.trim().take(512))

    private suspend fun embedText(text: String): FloatArray? {
        if (text.isBlank() || !modelFile.isFile) return null
        return lock.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    val eng = ensureEngine() ?: return@withContext null
                    val res = eng.computeEmbedding(
                        listOf(InputData.Text(text)),
                        EmbeddingOptions(normalize = true),
                    )
                    EmbeddingCodec.normalize(res.embedding)
                }.getOrNull()
            }
        }
    }
}
