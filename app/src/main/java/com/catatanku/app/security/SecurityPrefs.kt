package com.catatanku.app.security

import android.content.Context
import android.os.SystemClock

enum class LockTimeout(val ms: Long, val label: String) {
    IMMEDIATE(0L, "Segera"),
    SECONDS_15(15_000L, "15 detik"),
    MINUTE_1(60_000L, "1 menit");

    companion object {
        fun of(ms: Long): LockTimeout = entries.firstOrNull { it.ms == ms } ?: IMMEDIATE
    }
}

/**
 * Penyimpanan pengaturan keamanan dan privasi lokal (`sec_prefs`), terpisah dari preferensi UI umum
 * namun tetap menyelaraskan flag `lock` di `ui` demi kompatibilitas komponen sistem.
 */
object SecurityPrefs {
    private const val PREF_SEC = "sec_prefs"
    private const val PREF_UI = "ui"

    private fun sec(ctx: Context) = ctx.getSharedPreferences(PREF_SEC, Context.MODE_PRIVATE)
    private fun ui(ctx: Context) = ctx.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)

    fun isLockEnabled(ctx: Context): Boolean =
        sec(ctx).getBoolean("lock_enabled", ui(ctx).getBoolean("lock", false)) || hasAppPin(ctx)

    fun setLockEnabled(ctx: Context, on: Boolean) {
        sec(ctx).edit().putBoolean("lock_enabled", on).apply()
        ui(ctx).edit().putBoolean("lock", on).apply()
    }

    fun lockTimeout(ctx: Context): LockTimeout =
        LockTimeout.of(sec(ctx).getLong("lock_timeout_ms", LockTimeout.IMMEDIATE.ms))

    fun setLockTimeout(ctx: Context, t: LockTimeout) {
        sec(ctx).edit().putLong("lock_timeout_ms", t.ms).apply()
    }

    /** Perlindungan layar anti-screenshot & pratinjau Recent Apps; aktif bawaan. */
    fun isScreenShieldEnabled(ctx: Context): Boolean =
        sec(ctx).getBoolean("screen_shield", true)

    fun setScreenShieldEnabled(ctx: Context, on: Boolean) {
        sec(ctx).edit().putBoolean("screen_shield", on).apply()
    }

    /** Mode penyamaran keyboard (`IME_FLAG_NO_PERSONALIZED_LEARNING`); aktif bawaan. */
    fun isIncognitoKeyboardEnabled(ctx: Context): Boolean =
        sec(ctx).getBoolean("incognito_keyboard", true)

    fun setIncognitoKeyboardEnabled(ctx: Context, on: Boolean) {
        sec(ctx).edit().putBoolean("incognito_keyboard", on).apply()
    }

    /** Tandai sensitif & hapus otomatis papan klip setelah 30 detik; aktif bawaan. */
    fun isClipboardAutoClearEnabled(ctx: Context): Boolean =
        sec(ctx).getBoolean("clipboard_auto_clear", true)

    fun setClipboardAutoClearEnabled(ctx: Context, on: Boolean) {
        sec(ctx).edit().putBoolean("clipboard_auto_clear", on).apply()
    }

    /** Mode Offline Mutlak: memblokir semua permintaan unduhan jaringan. */
    fun isStrictOffline(ctx: Context): Boolean =
        sec(ctx).getBoolean("strict_offline", false)

    fun setStrictOffline(ctx: Context, on: Boolean) {
        sec(ctx).edit().putBoolean("strict_offline", on).apply()
    }

    // ---------- PIN / Sandi Mandiri Aplikasi ----------

    fun hasAppPin(ctx: Context): Boolean = !sec(ctx).getString("app_pin_hash", null).isNullOrBlank()

    fun setAppPin(ctx: Context, pin: CharArray) {
        val hash = AppPinCrypto.hashPin(pin)
        sec(ctx).edit().putString("app_pin_hash", hash).putBoolean("lock_enabled", true).apply()
        ui(ctx).edit().putBoolean("lock", true).apply()
        resetFailures(ctx)
    }

    fun clearAppPin(ctx: Context) {
        sec(ctx).edit().remove("app_pin_hash").apply()
        resetFailures(ctx)
    }

    fun verifyAppPin(ctx: Context, pin: CharArray): Boolean =
        AppPinCrypto.verifyPin(pin, sec(ctx).getString("app_pin_hash", null))

    fun failedAttempts(ctx: Context): Int = sec(ctx).getInt("failed_unlocks", 0)

    fun remainingLockoutMs(ctx: Context): Long {
        val untilElapsed = sec(ctx).getLong("lockout_until_elapsed", 0L)
        val untilWall = sec(ctx).getLong("lockout_until_wall", 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val remElapsed = if (untilElapsed > nowElapsed) untilElapsed - nowElapsed else 0L
        val remWall = if (untilWall > nowWall && untilWall - nowWall <= 600_000L) untilWall - nowWall else 0L
        return maxOf(remElapsed, remWall)
    }

    fun recordUnlockFailure(ctx: Context): Int {
        val next = failedAttempts(ctx) + 1
        val delay = AppPinCrypto.lockoutDelayMs(next)
        val ed = sec(ctx).edit().putInt("failed_unlocks", next)
        if (delay > 0L) {
            ed.putLong("lockout_until_elapsed", SystemClock.elapsedRealtime() + delay)
                .putLong("lockout_until_wall", System.currentTimeMillis() + delay)
        }
        ed.apply()
        return next
    }

    fun resetFailures(ctx: Context) {
        sec(ctx).edit()
            .remove("failed_unlocks")
            .remove("lockout_until_elapsed")
            .remove("lockout_until_wall")
            .apply()
    }

    fun isWipeAfterFailuresEnabled(ctx: Context): Boolean =
        sec(ctx).getBoolean("wipe_after_failures", false)

    fun setWipeAfterFailuresEnabled(ctx: Context, on: Boolean) {
        sec(ctx).edit().putBoolean("wipe_after_failures", on).apply()
    }

    // ---------- Kunci Enkripsi Cadangan Otomatis (CTKH) ----------

    fun hasAutoBackupEncryption(ctx: Context): Boolean {
        val p = sec(ctx)
        return !p.getString("ab_pub", null).isNullOrBlank() && !p.getString("ab_priv_wrapped", null).isNullOrBlank()
    }

    fun autoBackupHybridKeys(ctx: Context): BackupCrypto.AutoBackupKeys? {
        val p = sec(ctx)
        val pub = p.getString("ab_pub", null)?.takeIf { it.isNotBlank() } ?: return null
        val priv = p.getString("ab_priv_wrapped", null)?.takeIf { it.isNotBlank() } ?: return null
        return BackupCrypto.AutoBackupKeys(pub, priv)
    }

    fun setAutoBackupPassphrase(ctx: Context, passphrase: CharArray) {
        val keys = BackupCrypto.generateAutoBackupKeys(passphrase)
        sec(ctx).edit()
            .putString("ab_pub", keys.publicKeyBase64)
            .putString("ab_priv_wrapped", keys.wrappedPrivateKeyBase64)
            .apply()
    }

    fun verifyAutoBackupPassphrase(ctx: Context, passphrase: CharArray): Boolean {
        val wrapped = sec(ctx).getString("ab_priv_wrapped", null) ?: return false
        return BackupCrypto.verifyRecoveryPassphrase(passphrase, wrapped)
    }

    fun clearAutoBackupEncryption(ctx: Context) {
        sec(ctx).edit().remove("ab_pub").remove("ab_priv_wrapped").apply()
    }

    fun clearAllPreferences(ctx: Context) {
        sec(ctx).edit().clear().commit()
        ui(ctx).edit().clear().commit()
    }

    /** Hapus darurat seluruh basis data, lampiran, notifikasi aktif, dan kunci Keystore dari penyimpanan. */
    fun wipeStorageNow(ctx: Context) {
        val appCtx = ctx.applicationContext
        runCatching {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancelAll()
        }
        runCatching { com.catatanku.app.data.database.NoteDb.resetForTest() }
        val dbFile = appCtx.getDatabasePath("catatan.db")
        listOf(dbFile, java.io.File(dbFile.path + "-wal"), java.io.File(dbFile.path + "-shm"), java.io.File(dbFile.path + "-journal"))
            .forEach { DeviceVaultKey.shredFile(it) }
        java.io.File(appCtx.filesDir, "attachments").listFiles()?.forEach { DeviceVaultKey.shredFile(it) }
        java.io.File(appCtx.noBackupFilesDir ?: appCtx.filesDir, "ephemeral").listFiles()?.forEach { DeviceVaultKey.shredFile(it) }
        appCtx.cacheDir.listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else DeviceVaultKey.shredFile(f)
        }
        DeviceVaultKey.wipeKeys(appCtx)
        clearAllPreferences(appCtx)
    }
}
