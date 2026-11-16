package com.catatanku.app.security

/**
 * Validasi input dan URI eksternal yang dapat diuji langsung di JVM tanpa ketergantungan Android.
 */
object InputSecurity {
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

    fun sanitizeLogText(text: String): String =
        text.replace(Regex("[\r\n\u0000]"), " ").take(200)
}
