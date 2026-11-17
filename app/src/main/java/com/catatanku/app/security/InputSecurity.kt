package com.catatanku.app.security

/**
 * Validasi input dan URI eksternal yang dapat diuji langsung di JVM tanpa ketergantungan Android.
 */
object InputSecurity {
    const val MAX_IMAGE_SOURCE_BYTES = 32L * 1024 * 1024
    const val MAX_IMAGE_DIMENSION_PX = 20_000
    const val MAX_IMAGE_PIXELS = 100_000_000L

    private val BIDI_AND_INVISIBLE = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")

    fun isSafeExternalContentUri(
        scheme: String?,
        authority: String?,
        userInfo: String?,
        host: String?,
        appPackageName: String,
    ): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        if (!userInfo.isNullOrEmpty()) return false
        val rawAuth = authority?.lowercase()?.trim().orEmpty()
        if (rawAuth.isEmpty() || ".." in rawAuth || '@' in rawAuth) return false
        val pkg = appPackageName.lowercase()
        val hostNorm = host?.lowercase()?.trim().orEmpty()
        if (rawAuth == pkg || rawAuth.startsWith("$pkg.")) return false
        if (hostNorm.isNotEmpty() && (hostNorm == pkg || hostNorm.startsWith("$pkg."))) return false
        return true
    }

    /** Membersihkan karakter kontrol dan karakter arah teks tersembunyi (Bidi Trojan Source) dari teks luar. */
    fun sanitizeExternalText(text: String, maxChars: Int = 100_000): String =
        text.replace(BIDI_AND_INVISIBLE, "").trim().take(maxChars)

    /** Menolak bom piksel atau berkas gambar berdimensi ekstrem sebelum didekode ke bitmap. */
    fun isSafeImageDimensions(width: Int, height: Int, sourceBytes: Long = -1L): Boolean {
        if (sourceBytes > MAX_IMAGE_SOURCE_BYTES) return false
        if (width <= 0 || height <= 0) return false
        if (width > MAX_IMAGE_DIMENSION_PX || height > MAX_IMAGE_DIMENSION_PX) return false
        val pixels = width.toLong() * height.toLong()
        return pixels in 1L..MAX_IMAGE_PIXELS
    }

    fun sanitizeLogText(text: String): String =
        text.replace(Regex("[\r\n\u0000]"), " ").take(200)
}
