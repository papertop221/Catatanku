package com.catatanku.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pengelolaan lampiran gambar dan catatan suara di penyimpanan internal aplikasi (`filesDir/attachments`).
 * Gambar dikecilkan ke ≤ 1600px (JPEG) agar hemat ruang; suara direkam sebagai WAV 16 kHz mono agar bisa
 * langsung diputar ulang sekaligus dibaca oleh model multimodal lokal (Gemma 4 E2B lewat LiteRT-LM).
 */
object Attachments {
    const val MAX_IMAGE_PX = 1600
    const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SEC = SAMPLE_RATE * 2   // 16-bit mono

    fun dir(ctx: Context): File = File(ctx.filesDir, "attachments").apply { mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), File(name).name)
    fun isImage(name: String): Boolean = name.startsWith("img_")
    fun isAudio(name: String): Boolean = name.startsWith("aud_")

    /** Menyalin dan mengecilkan gambar dari [uri] ke penyimpanan internal; mengembalikan nama berkasnya. */
    fun importImage(ctx: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Gambar tidak bisa dibaca.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Gambar tidak bisa dibuka.")
        val longest = max(decoded.width, decoded.height)
        val scaled = if (longest > MAX_IMAGE_PX) {
            val ratio = MAX_IMAGE_PX.toFloat() / longest
            val w = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val name = "img_${System.currentTimeMillis()}_${(100..999).random()}.jpg"
        val out = file(ctx, name)
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        scaled.recycle()
        return name
    }

    /** Menyalin berkas suara yang dibagikan dari aplikasi lain (maks 25 MB). */
    fun importAudio(ctx: Context, uri: Uri): String {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        val ext = when {
            "wav" in mime -> "wav"
            "ogg" in mime || "opus" in mime -> "ogg"
            "mp3" in mime || "mpeg" in mime -> "mp3"
            else -> "m4a"
        }
        val name = "aud_${System.currentTimeMillis()}_${(100..999).random()}.$ext"
        val out = file(ctx, name)
        var total = 0L
        val maxBytes = 25L * 1024 * 1024
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) { out.delete(); error("Berkas suara terlalu besar (maks 25 MB).") }
                    output.write(buf, 0, n)
                }
            }
        } ?: error("Berkas suara tidak bisa dibuka.")
        if (total < 100) { out.delete(); error("Berkas suara kosong.") }
        return name
    }

    /** Perkiraan durasi rekaman suara dalam detik. Untuk WAV 16 kHz mono dihitung langsung dari ukuran berkas. */
    fun audioDurationSec(ctx: Context, name: String): Int {
        val f = file(ctx, name)
        if (!f.exists()) return 0
        if (name.endsWith(".wav")) return ((f.length() - 44).coerceAtLeast(0) / BYTES_PER_SEC).toInt().coerceAtLeast(1)
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt().coerceAtLeast(1)
            } finally { r.release() }
        }.getOrDefault(1)
    }

    fun formatDuration(sec: Int): String = "%d.%02d".format(sec / 60, sec % 60)

    /** Membuang berkas lampiran yang tidak lagi dipakai oleh catatan mana pun (termasuk yang masih di Sampah). */
    fun cleanup(ctx: Context, notes: List<Note>) {
        val keep = notes.flatMapTo(HashSet()) { it.attachments }
        dir(ctx).listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
    }
}

/**
 * Perekam catatan suara ke berkas WAV 16 kHz 16-bit mono. Format ini ringan (±1,9 MB per menit),
 * bisa diputar di semua versi Android, dan langsung diterima oleh `Content.AudioFile` pada LiteRT-LM.
 */
class VoiceRecorder(private val ctx: Context) {
    var recording by mutableStateOf(false); private set
    var seconds by mutableStateOf(0); private set
    private var record: AudioRecord? = null
    private var outFile: File? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            Attachments.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, Attachments.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 8192),
            )
        } catch (_: Throwable) { return false }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { runCatching { ar.release() }; return false }
        val target = Attachments.file(ctx, "aud_${System.currentTimeMillis()}_${(100..999).random()}.wav")
        outFile = target; record = ar; seconds = 0; recording = true
        try { ar.startRecording() } catch (_: Throwable) { recording = false; runCatching { ar.release() }; return false }
        worker = thread(name = "catatanku-mic") {
            var pcmBytes = 0L
            try {
                FileOutputStream(target).use { out ->
                    out.write(ByteArray(44))    // tempat untuk kepala WAV
                    val buf = ByteArray(4096)
                    while (recording) {
                        val n = ar.read(buf, 0, buf.size)
                        if (n > 0) {
                            out.write(buf, 0, n)
                            pcmBytes += n
                            seconds = (pcmBytes / (Attachments.SAMPLE_RATE * 2)).toInt()
                        }
                    }
                }
                writeWavHeader(target, pcmBytes)
            } catch (_: Throwable) {
                target.delete()
            } finally {
                runCatching { ar.stop() }
                runCatching { ar.release() }
            }
        }
        return true
    }

    /** Menghentikan rekaman dan mengembalikan nama berkas WAV-nya (atau null bila terlalu pendek/gagal). */
    fun stop(): String? {
        if (!recording) return null
        recording = false
        runCatching { worker?.join(1500) }
        worker = null; record = null
        val f = outFile; outFile = null
        if (f == null || !f.exists() || f.length() <= 1600) { f?.delete(); return null }
        return f.name
    }

    fun cancel() {
        val f = outFile
        stop()
        f?.delete()
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        if (!file.exists()) return
        val totalDataLen = pcmBytes + 36
        val byteRate = Attachments.SAMPLE_RATE * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen.toInt())
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                                  // ukuran sub-chunk PCM
            putShort(1)                                 // AudioFormat 1 = PCM
            putShort(1)                                 // 1 kanal (mono)
            putInt(Attachments.SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)                                 // block align
            putShort(16)                                // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.toInt())
        }.array()
        RandomAccessFile(file, "rw").use { raf -> raf.seek(0); raf.write(header) }
    }
}

/** Pemutar catatan suara di dalam editor. */
class AudioPlayer {
    var playing by mutableStateOf<String?>(null); private set
    private var player: MediaPlayer? = null

    fun toggle(ctx: Context, name: String) {
        if (playing == name) { stop(); return }
        stop()
        val f = Attachments.file(ctx, name)
        if (!f.exists()) return
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing = name
        } catch (_: Throwable) {
            stop()
        }
    }

    fun stop() {
        playing = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
