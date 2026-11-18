package com.catatanku.app.data.repository

import android.content.Context
import com.catatanku.app.domain.usecase.NoteTemplate
import com.catatanku.app.domain.usecase.Templates
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.VaultCrypto

/**
 * Templat milik pengguna. Isinya bisa memuat hal pribadi, jadi disimpan terenkripsi AES-256-GCM dengan kunci data
 * yang sama dengan catatan (Android Keystore), bukan teks polos di preferensi. Hapus darurat ikut memusnahkannya
 * karena kuncinya dan semua preferensi dihapus.
 */
class TemplateStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("templates", Context.MODE_PRIVATE)

    fun load(): List<NoteTemplate> {
        val stored = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            Templates.decode(VaultCrypto.decryptStringIfNeeded(DeviceVaultKey.getDataKey(ctx), AAD, stored))
        }.getOrDefault(emptyList())
    }

    fun save(list: List<NoteTemplate>) {
        val user = list.filter { !it.builtIn }.take(Templates.MAX_USER_TEMPLATES)
        if (user.isEmpty()) { prefs.edit().remove(KEY).apply(); return }
        prefs.edit().putString(KEY, VaultCrypto.encryptString(DeviceVaultKey.getDataKey(ctx), AAD, Templates.encode(user))).apply()
    }

    private companion object {
        const val KEY = "user_v1"
        const val AAD = "templates:user"
    }
}
