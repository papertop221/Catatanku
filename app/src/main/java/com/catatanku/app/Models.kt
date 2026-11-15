package com.catatanku.app

/**
 * Model yang bisa diunduh satu ketukan. Semua berlisensi Apache-2.0 dan bisa diunduh tanpa login
 * (diverifikasi 15 Nov 2026). URL dikunci ke satu revisi agar ukuran dan SHA-256 selalu cocok.
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
) {
    val sizeLabel: String get() = "%.1f GB".format(bytes / 1e9)
}

object ModelCatalog {
    const val IMPORT_ID = "import"
    private const val HF = "https://huggingface.co/litert-community"

    val all = listOf(
        ModelSpec(
            "gemma4-e2b", "Gemma 4 E2B",
            "Pilihan utama. Bahasa Indonesia baik; semua fitur AI aplikasi ini diuji dengan model ini.",
            "gemma-4-E2B-it.litertlm",
            "$HF/gemma-4-E2B-it-litert-lm/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/gemma-4-E2B-it.litertlm",
            2_588_147_712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            minRamGb = 6, recRamGb = 8, inputChars = 6000,
        ),
        ModelSpec(
            "qwen25-15b", "Qwen2.5 1.5B",
            "Lebih kecil untuk HP dengan RAM terbatas. Cukup untuk tag, ejaan, dan tanya-jawab; " +
                "merapikan dan daftar tugas kurang andal.",
            "Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm",
            "$HF/Qwen2.5-1.5B-Instruct/resolve/60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            1_597_922_856, "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
            minRamGb = 4, recRamGb = 6, inputChars = 2800,
        ),
        ModelSpec(
            "gemma4-e4b", "Gemma 4 E4B",
            "Paling mampu sekaligus paling berat dan lambat. Belum diuji dengan aplikasi ini.",
            "gemma-4-E4B-it.litertlm",
            "$HF/gemma-4-E4B-it-litert-lm/resolve/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm",
            3_659_530_240, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            minRamGb = 8, recRamGb = 12, inputChars = 6000,
        ),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** Model terbaik yang masih nyaman untuk RAM perangkat; kalau tak ada, yang paling ringan. */
    fun recommended(ramGb: Int): ModelSpec =
        all.filter { it.recRamGb <= ramGb }.maxByOrNull { it.recRamGb } ?: all.minBy { it.minRamGb }
}
