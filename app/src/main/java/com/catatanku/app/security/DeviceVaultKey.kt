package com.catatanku.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pengelola kunci enkripsi perangkat keras berbasis Android Keystore (`catatanku_vault_kek_v1`).
 * Membungkus kunci data AES-256 acak (`vault_dek.bin` di `noBackupFilesDir`) sehingga berkas basis data
 * dan lampiran terenkripsi di penyimpanan internal sekaligus dapat dihancurkan seketika (crypto-shredding).
 */
object DeviceVaultKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "catatanku_vault_kek_v1"
    private const val DEK_FILE = "vault_dek.bin"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val lock = Any()
    @Volatile private var cachedDek: ByteArray? = null
    @Volatile private var testProvider: VaultKeyProvider? = null

    fun setTestProviderForUnitTest(provider: VaultKeyProvider?) {
        synchronized(lock) {
            cachedDek?.let { Arrays.fill(it, 0.toByte()) }
            cachedDek = null
            testProvider = provider
        }
    }

    fun hasHardwareOrKeystoreBacking(ctx: Context): Boolean {
        if (testProvider != null) return false
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.containsAlias(KEY_ALIAS) && dekFile(ctx).isFile
        }.getOrDefault(false)
    }

    fun getDataKey(ctx: Context): ByteArray {
        testProvider?.let { return it.getDataKey() }
        synchronized(lock) {
            cachedDek?.let { return it.copyOf() }
            val appCtx = ctx.applicationContext
            val file = dekFile(appCtx)
            val dek = if (file.isFile) {
                unwrapDek(file.readBytes())
            } else {
                createAndWrapDek(file)
            }
            cachedDek = dek.copyOf()
            return dek
        }
    }

    /** Menghapus salinan DEK dari RAM saat aplikasi dikunci (tanpa menghapus kunci di Keystore). */
    fun clearMemoryCache() {
        synchronized(lock) {
            cachedDek?.let { Arrays.fill(it, 0.toByte()) }
            cachedDek = null
        }
    }

    /** Crypto-shredding: menghancurkan kunci Keystore dan menimpa berkas DEK sehingga semua data tak dapat didekripsi lagi. */
    fun wipeKeys(ctx: Context) {
        synchronized(lock) {
            cachedDek?.let { Arrays.fill(it, 0.toByte()) }
            cachedDek = null
            testProvider?.destroyKeys()
            val file = dekFile(ctx.applicationContext)
            shredFile(file)
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun dekFile(ctx: Context): File = File(ctx.noBackupFilesDir ?: ctx.filesDir, DEK_FILE)

    private fun getOrCreateKek(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return kg.generateKey()
    }

    private fun createAndWrapDek(target: File): ByteArray {
        val kek = getOrCreateKek()
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        val ct = cipher.doFinal(dek)
        val blob = ByteBuffer.allocate(1 + 4 + iv.size + ct.size)
            .put(1.toByte())
            .putInt(iv.size)
            .put(iv)
            .put(ct)
            .array()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$DEK_FILE.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(target)) {
            target.writeBytes(blob)
            tmp.delete()
        }
        return dek
    }

    private fun unwrapDek(blob: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(blob)
        val ver = buf.get().toInt()
        require(ver == 1) { "Versi kunci brankas tidak didukung." }
        val ivLen = buf.int
        require(ivLen in 12..32 && buf.remaining() > ivLen + 16) { "Berkas kunci brankas rusak." }
        val iv = ByteArray(ivLen).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val kek = ks.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Kunci perangkat keras Keystore tidak ditemukan.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun shredFile(file: File) {
        if (!file.exists() || !file.isFile) return
        runCatching {
            val len = file.length()
            if (len in 1..64L * 1024 * 1024) {
                RandomAccessFile(file, "rw").use { raf ->
                    val zeros = ByteArray(4096)
                    var rem = len
                    raf.seek(0)
                    while (rem > 0) {
                        val n = minOf(zeros.size.toLong(), rem).toInt()
                        raf.write(zeros, 0, n)
                        rem -= n
                    }
                    raf.fd.sync()
                }
            }
        }
        file.delete()
    }
}
