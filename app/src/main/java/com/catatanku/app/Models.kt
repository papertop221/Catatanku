package com.catatanku.app

/**
 * Model yang bisa diunduh satu ketukan. Semua berlisensi Apache-2.0 dan bisa diunduh tanpa login
 * (diverifikasi 15 Nov 2026). URL dikunci ke satu revisi agar ukuran dan SHA-256 selalu cocok.
 *
 * Angka RAM berasal dari pengukuran di desktop (CPU, puncak RSS proses) pada 15 Nov 2026: Gemma 4 E2B ±1,8–1,9 GB,
 * Qwen2.5 1.5B ±2,4 GB, Qwen3 1.7B INT4 ±2,8 GB (±2,0 GB dengan jendela 2.048 token). Angka di HP belum diukur.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val blurb: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val minRamGb: Int,      // di bawah ini kemungkinan besar gagal dimuat (perkiraan)
    val recRamGb: Int,      // nyaman dipakai (perkiraan)
    val inputChars: Int,    // batas teks catatan yang dikirim ke model
    val tier: String,       // satu kata untuk daftar ringkas: Ringan / Seimbang / Utama / Terberat
    val thinks: Boolean = false,   // model bernalar dulu secara bawaan; dimatikan agar cepat
    val legacy: Boolean = false,   // tidak ditawarkan lagi; hanya tampil bila sudah terpasang
    val ramMb: Int = 0,            // puncak memori saat dipakai, diukur di desktop; 0 = belum diukur
    val lightRamMb: Int = 0,       // sama, dalam Mode ringan
) {
    val sizeLabel: String get() = "%.1f GB".format(bytes / 1e9)

    /** Perkiraan memori yang dipakai model saat berjalan. Yang belum diukur ditaksir dari ukuran berkasnya. */
    fun workingBytes(light: Boolean): Long {
        val mb = if (light && lightRamMb > 0) lightRamMb else ramMb
        return if (mb > 0) mb * 1_000_000L else bytes * 3 / 4
    }
}

object ModelCatalog {
    const val IMPORT_ID = "import"
    private const val HF = "https://huggingface.co/litert-community"

    val all = listOf(
        ModelSpec(
            "gemma4-e2b", "Gemma 4 E2B",
            "Pilihan utama: tercepat dan paling hemat RAM di antara pilihan ini (±1,5–2 GB saat dipakai). " +
                "Bahasa Indonesia baik; semua fitur AI aplikasi ini diuji dengan model ini.",
            "gemma-4-E2B-it.litertlm",
            "$HF/gemma-4-E2B-it-litert-lm/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/gemma-4-E2B-it.litertlm",
            2_588_147_712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            minRamGb = 4, recRamGb = 6, inputChars = 6000, tier = "Utama", ramMb = 1900, lightRamMb = 1650,
        ),
        ModelSpec(
            "qwen3-17b", "Qwen3 1.7B",
            "Unduhan terkecil, untuk kuota atau penyimpanan terbatas. Ringkasan, daftar tugas, dan tanya-jawab cukup baik; " +
                "tag dan perbaikan ejaan kurang andal. Lebih lambat dan memakai RAM lebih banyak daripada Gemma 4 E2B " +
                "(±2–2,8 GB), jadi nyalakan Mode ringan.",
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "$HF/Qwen3-1.7B/resolve/c421cc3775575372ac2ca4aa1bebc97300ae36b6/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            977_184_032, "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
            minRamGb = 6, recRamGb = 8, inputChars = 2800, tier = "Unduhan kecil", thinks = true, ramMb = 2800, lightRamMb = 2000,
        ),
        ModelSpec(
            "gemma4-e4b", "Gemma 4 E4B",
            "Paling mampu sekaligus paling berat dan lambat. Belum diuji dengan aplikasi ini.",
            "gemma-4-E4B-it.litertlm",
            "$HF/gemma-4-E4B-it-litert-lm/resolve/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm",
            3_659_530_240, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            minRamGb = 8, recRamGb = 12, inputChars = 6000, tier = "Terberat",
        ),
        // Tidak lagi ditawarkan: pada pengukuran, Gemma 4 E2B lebih cepat, lebih hemat RAM, dan lebih baik.
        // Tetap dikenali agar pengguna yang sudah mengunduhnya bisa memakai atau menghapusnya.
        ModelSpec(
            "qwen25-15b", "Qwen2.5 1.5B",
            "Model lama. Gemma 4 E2B lebih cepat, lebih hemat RAM, dan lebih baik; pertimbangkan beralih.",
            "Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm",
            "$HF/Qwen2.5-1.5B-Instruct/resolve/60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            1_597_922_856, "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
            minRamGb = 6, recRamGb = 8, inputChars = 2800, tier = "Lama", legacy = true, ramMb = 2400,
        ),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /**
     * Gemma 4 E2B untuk semua HP: pada pengukuran ia memakai RAM paling sedikit, sehingga model "lebih kecil"
     * bukan pilihan yang lebih ringan. HP ber-RAM kecil dibantu Mode ringan, bukan model lain.
     */
    fun recommended(ramGb: Int): ModelSpec = all.first()

    /** RAM di bawah ini sebaiknya memakai Mode ringan sejak awal. */
    const val LIGHT_BELOW_GB = 7
}
