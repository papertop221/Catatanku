package com.catatanku.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.catatanku.app.domain.model.Note
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.InputSecurity
import com.catatanku.app.security.VaultCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Arrays
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pengelolaan lampiran gambar dan catatan suara terenkripsi (AES-256-GCM `CTKA\x01`) di penyimpanan internal
 * (`filesDir/attachments`). Gambar dibersihkan dari metadata EXIF; seluruh audio ditranskode ulang ke WAV 16 kHz
 * mono agar bebas metadata asing dan siap dibaca model multimodal tanpa menulis berkas polos sementara ke disk.
 */
object Attachments {
    const val MAX_IMAGE_PX = 1600
    const val OCR_DERIVATIVE_PX = 1024
    const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SEC = SAMPLE_RATE * 2   // 16-bit mono
    private val rng = SecureRandom()

    internal fun randomTag(): String {
        val b = ByteArray(4).also { rng.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "attachments").apply { mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), File(name).name)
    fun isImage(name: String): Boolean = name.startsWith("img_")
    fun isAudio(name: String): Boolean = name.startsWith("aud_")

    private fun ephemeralDir(ctx: Context): File =
        File(ctx.noBackupFilesDir ?: ctx.filesDir, "ephemeral").apply { mkdirs() }

    fun existingNames(ctx: Context): Set<String> =
        dir(ctx).list()?.toHashSet() ?: emptySet()

    fun exists(ctx: Context, name: String): Boolean = file(ctx, name).isFile

    /**
     * Hanya menerima URI `content://` dari penyedia sistem/aplikasi lain; menolak `file://` maupun URI
     * yang menunjuk otoritas paket ini sendiri agar tidak membaca berkas internal sandbox.
     */
    fun isSafeExternalUri(ctx: Context, uri: Uri): Boolean =
        InputSecurity.isSafeExternalContentUri(
            scheme = uri.scheme,
            authority = uri.authority,
            userInfo = uri.userInfo,
            host = uri.host,
            appPackageName = ctx.packageName,
        )

    private inline fun <T> withKey(ctx: Context, block: (ByteArray) -> T): T {
        val key = DeviceVaultKey.getDataKey(ctx)
        return try {
            block(key)
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }

    fun openDecryptedStream(ctx: Context, file: File): InputStream {
        val key = DeviceVaultKey.getDataKey(ctx)
        return try {
            VaultCrypto.openAttachmentDecryptingStream(key, file.name, FileInputStream(file))
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }

    fun readDecryptedBytes(ctx: Context, name: String): ByteArray? {
        val f = file(ctx, name)
        if (!f.isFile) return null
        return withKey(ctx) { key ->
            FileInputStream(f).use { inp -> VaultCrypto.decryptAttachmentBytes(key, f.name, inp) }
        }
    }

    fun writeEncryptedFromStream(ctx: Context, name: String, plainIn: InputStream) {
        val outFile = file(ctx, name)
        val tmp = File(dir(ctx), "${outFile.name}.tmp")
        withKey(ctx) { key ->
            try {
                BufferedOutputStream(FileOutputStream(tmp), 16 * 1024).use { out ->
                    VaultCrypto.encryptAttachment(key, outFile.name, plainIn, out)
                }
                outFile.delete()
                if (!tmp.renameTo(outFile)) error("Gagal menyimpan lampiran terenkripsi.")
            } catch (t: Throwable) {
                DeviceVaultKey.shredFile(tmp)
                throw t
            }
        }
    }

    /** Mengenkripsi lampiran polos peninggalan versi sebelum 1.9 agar seluruh media di disk terenkripsi. */
    fun migrateLegacyAttachments(ctx: Context) {
        val files = dir(ctx).listFiles() ?: return
        val hdr = ByteArray(8)
        for (f in files) {
            if (!f.isFile || f.name.endsWith(".tmp")) continue
            val isEnc = runCatching {
                FileInputStream(f).use { it.read(hdr) }.let { n ->
                    n >= 5 && VaultCrypto.isEncryptedAttachmentHeader(hdr.copyOf(n))
                }
            }.getOrDefault(true)
            if (isEnc) continue
            val tmp = File(f.parentFile, "${f.name}.enc_mig")
            runCatching {
                withKey(ctx) { key ->
                    FileInputStream(f).use { inp ->
                        BufferedOutputStream(FileOutputStream(tmp), 16 * 1024).use { out ->
                            VaultCrypto.encryptAttachment(key, f.name, inp, out)
                        }
                    }
                }
                DeviceVaultKey.shredFile(f)
                tmp.renameTo(f)
            }.onFailure {
                DeviceVaultKey.shredFile(tmp)
            }
        }
    }

    /** Membersihkan sisa berkas sementara di cacheDir dan ephemeral agar tidak meninggalkan jejak polos. */
    fun sweepStaleTempFiles(ctx: Context) {
        runCatching {
            ephemeralDir(ctx).listFiles()?.forEach { DeviceVaultKey.shredFile(it) }
        }
        runCatching {
            ctx.cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("ocr_") || n.startsWith("restore_staging_") || n.startsWith("catatanku_enc_")) {
                    if (f.isDirectory) f.deleteRecursively() else DeviceVaultKey.shredFile(f)
                }
            }
        }
    }

    /** Menyalin, menyaring bom piksel, mengecilkan gambar (≤ 1600px, tanpa EXIF), lalu mengenkripsinya ke penyimpanan internal. */
    fun importImage(ctx: Context, uri: Uri): String {
        if (!isSafeExternalUri(ctx, uri)) error("Sumber gambar tidak diizinkan.")
        val sourceSize = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (sourceSize > InputSecurity.MAX_IMAGE_SOURCE_BYTES) error("Ukuran berkas gambar terlalu besar (maks 32 MB).")

        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                if (!InputSecurity.isSafeImageDimensions(info.size.width, info.size.height, sourceSize)) {
                    error("Dimensi gambar tidak wajar atau terlalu besar.")
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_IMAGE_PX) {
                    val ratio = MAX_IMAGE_PX.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                        (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (!InputSecurity.isSafeImageDimensions(bounds.outWidth, bounds.outHeight, sourceSize)) {
                error("Gambar tidak bisa dibaca atau dimensinya terlalu besar.")
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Gambar tidak bisa dibuka.")
            val longest = max(decoded.width, decoded.height)
            if (longest > MAX_IMAGE_PX) {
                val ratio = MAX_IMAGE_PX.toFloat() / longest
                val w = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                try {
                    Bitmap.createScaledBitmap(decoded, w, h, true)
                } finally {
                    decoded.recycle()
                }
            } else decoded
        }

        val name = "img_${System.currentTimeMillis()}_${randomTag()}.jpg"
        val plainJpeg = ByteArrayOutputStream()
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, plainJpeg)
        } finally {
            bmp.recycle()
        }
        val bytes = plainJpeg.toByteArray()
        try {
            writeEncryptedFromStream(ctx, name, ByteArrayInputStream(bytes))
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
        return name
    }

    /**
     * Menghasilkan byte gambar JPEG beresolusi ≤ [maxPx] langsung di RAM untuk OCR/AI
     * tanpa menulis berkas gambar polos sementara ke disk.
     */
    fun createOcrDerivativeBytes(ctx: Context, name: String, maxPx: Int = OCR_DERIVATIVE_PX): ByteArray? {
        val raw = readDecryptedBytes(ctx, name) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            if (longest <= maxPx) return raw
            var sample = 1
            while (longest / (sample * 2) >= maxPx) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val curLongest = max(decoded.width, decoded.height)
            val scaled = if (curLongest > maxPx) {
                val ratio = maxPx.toFloat() / curLongest
                val w = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                try { Bitmap.createScaledBitmap(decoded, w, h, true) } finally { decoded.recycle() }
            } else decoded
            try {
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            } finally {
                scaled.recycle()
            }
        } finally {
            // Jangan nol-kan `raw` bila `raw` sendiri yang dikembalikan langsung saat longest <= maxPx.
        }
    }

    fun decodeSampled(ctx: Context, name: String, maxPx: Int): Bitmap? {
        val raw = readDecryptedBytes(ctx, name) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (!InputSecurity.isSafeImageDimensions(bounds.outWidth, bounds.outHeight)) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    /**
     * Mengimpor suara eksternal dan SELALU mentranskode ulang ke WAV 16 kHz mono agar bebas metadata
     * tersembunyi, menolak berkas non-audio palsu, lalu mengenkripsinya ke penyimpanan internal.
     */
    fun importAudio(ctx: Context, uri: Uri): String {
        if (!isSafeExternalUri(ctx, uri)) error("Sumber audio tidak diizinkan.")
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        val ext = when {
            "wav" in mime -> "wav"
            "ogg" in mime || "opus" in mime -> "ogg"
            "mp3" in mime || "mpeg" in mime -> "mp3"
            else -> "m4a"
        }
        val tag = randomTag()
        val rawTmp = File(ephemeralDir(ctx), "imp_aud_$tag.$ext")
        val wavTmp = File(ephemeralDir(ctx), "imp_wav_$tag.wav")
        try {
            var total = 0L
            val maxBytes = 25L * 1024 * 1024
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(rawTmp).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) error("Berkas suara terlalu besar (maks 25 MB).")
                        output.write(buf, 0, n)
                    }
                }
            } ?: error("Berkas suara tidak bisa dibuka.")
            if (total < 100) error("Berkas suara kosong.")

            if (!transcodeToWav16k(rawTmp, wavTmp)) {
                error("Berkas suara tidak valid atau formatnya tidak didukung.")
            }
            val wavName = "aud_${System.currentTimeMillis()}_${randomTag()}.wav"
            FileInputStream(wavTmp).use { inp -> writeEncryptedFromStream(ctx, wavName, inp) }
            return wavName
        } finally {
            DeviceVaultKey.shredFile(rawTmp)
            DeviceVaultKey.shredFile(wavTmp)
        }
    }

    private fun transcodeToWav16k(src: File, dst: File): Boolean = runCatching {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(src.absolutePath)
            val trackIdx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(trackIdx)
            val fmt = extractor.getTrackFormat(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            val srcRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else return false
            val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            if (srcRate !in 8_000..192_000 || channels !in 1..8) return false

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val maxSamples = SAMPLE_RATE * 5 * 60
            val deadlineNs = System.nanoTime() + 10_000_000_000L
            var writtenSamples = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var posAcc = 0.0
            val step = srcRate.toDouble() / SAMPLE_RATE

            BufferedOutputStream(FileOutputStream(dst), 16 * 1024).use { out ->
                out.write(ByteArray(44))
                while (!outputDone && writtenSamples < maxSamples && System.nanoTime() < deadlineNs) {
                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        val buf = c.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            var frameIdx = posAcc.toInt()
                            while (frameIdx < frames && writtenSamples < maxSamples) {
                                var sum = 0
                                for (ch in 0 until channels) sum += shorts.get(frameIdx * channels + ch)
                                val mono = (sum / channels).toShort()
                                out.write(mono.toInt() and 0xFF)
                                out.write((mono.toInt() ushr 8) and 0xFF)
                                writtenSamples++
                                posAcc += step
                                frameIdx = posAcc.toInt()
                            }
                            posAcc -= frames
                            if (posAcc < 0) posAcc = 0.0
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val pcmBytes = writtenSamples * 2
            if (pcmBytes < 1600) return false
            RandomAccessFile(dst, "rw").use { raf -> raf.seek(0); raf.write(wavHeader(pcmBytes)) }
            true
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }.getOrDefault(false)

    internal fun wavHeader(pcmBytes: Long): ByteArray {
        val totalDataLen = pcmBytes + 36
        val byteRate = SAMPLE_RATE * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen.toInt())
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.toInt())
        }.array()
    }

    fun audioDurationSec(ctx: Context, name: String): Int {
        val f = file(ctx, name)
        if (!f.exists()) return 0
        if (name.endsWith(".wav")) {
            return runCatching {
                openDecryptedStream(ctx, f).use { inp ->
                    val hdr = ByteArray(44)
                    var read = 0
                    while (read < 44) {
                        val n = inp.read(hdr, read, 44 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == 44 && hdr[0] == 'R'.code.toByte() && hdr[36] == 'd'.code.toByte()) {
                        val dataLen = ByteBuffer.wrap(hdr, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        (dataLen / BYTES_PER_SEC).toInt().coerceAtLeast(1)
                    } else {
                        var total = read.toLong()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                        }
                        ((total - 44).coerceAtLeast(0) / BYTES_PER_SEC).toInt().coerceAtLeast(1)
                    }
                }
            }.getOrDefault(1)
        }
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt().coerceAtLeast(1)
            } finally { r.release() }
        }.getOrDefault(1)
    }

    fun formatDuration(sec: Int): String = "%d.%02d".format(sec / 60, sec % 60)

    fun cleanup(ctx: Context, notes: List<Note>) {
        val keep = notes.flatMapTo(HashSet()) { it.attachments }
        dir(ctx).listFiles()?.forEach { f ->
            if (f.name !in keep) DeviceVaultKey.shredFile(f)
        }
    }
}

class VoiceRecorder(private val ctx: Context) {
    var recording by mutableStateOf(false); private set
    var seconds by mutableStateOf(0); private set
    private var record: AudioRecord? = null
    private var tempFile: File? = null
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
        val ephDir = File(ctx.noBackupFilesDir ?: ctx.filesDir, "ephemeral").apply { mkdirs() }
        val target = File(ephDir, "rec_tmp_${Attachments.randomTag()}.wav")
        tempFile = target; record = ar; seconds = 0; recording = true
        try { ar.startRecording() } catch (_: Throwable) { recording = false; runCatching { ar.release() }; return false }
        worker = thread(name = "catatanku-mic") {
            var pcmBytes = 0L
            try {
                BufferedOutputStream(FileOutputStream(target), 16 * 1024).use { out ->
                    out.write(ByteArray(44))
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
                DeviceVaultKey.shredFile(target)
            } finally {
                runCatching { ar.stop() }
                runCatching { ar.release() }
            }
        }
        return true
    }

    fun stop(): String? {
        if (!recording) return null
        recording = false
        runCatching { worker?.join(1500) }
        worker = null; record = null
        val f = tempFile; tempFile = null
        if (f == null || !f.exists() || f.length() <= 1600) {
            if (f != null) DeviceVaultKey.shredFile(f)
            return null
        }
        val finalName = "aud_${System.currentTimeMillis()}_${Attachments.randomTag()}.wav"
        return try {
            BufferedInputStream(FileInputStream(f), 16 * 1024).use { inp ->
                Attachments.writeEncryptedFromStream(ctx, finalName, inp)
            }
            finalName
        } catch (_: Throwable) {
            null
        } finally {
            DeviceVaultKey.shredFile(f)
        }
    }

    fun cancel() {
        val f = tempFile
        recording = false
        runCatching { worker?.join(1500) }
        worker = null; record = null; tempFile = null
        if (f != null) DeviceVaultKey.shredFile(f)
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { raf -> raf.seek(0); raf.write(Attachments.wavHeader(pcmBytes)) }
    }
}

private class MemoryAudioDataSource(private var data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= data.size) return -1
        val avail = (data.size - position.toInt()).coerceAtMost(size)
        if (avail <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, avail)
        return avail
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {
        Arrays.fill(data, 0.toByte())
    }
}

class AudioPlayer {
    var playing by mutableStateOf<String?>(null); private set
    private var player: MediaPlayer? = null
    private var source: MemoryAudioDataSource? = null

    fun toggle(ctx: Context, name: String) {
        if (playing == name) { stop(); return }
        stop()
        val bytes = runCatching { Attachments.readDecryptedBytes(ctx, name) }.getOrNull() ?: return
        try {
            val ds = MemoryAudioDataSource(bytes)
            source = ds
            player = MediaPlayer().apply {
                setDataSource(ds)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing = name
        } catch (_: Throwable) {
            Arrays.fill(bytes, 0.toByte())
            stop()
        }
    }

    fun stop() {
        playing = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        runCatching { source?.close() }
        player = null
        source = null
    }
}
