# Arsitektur

Satu modul (`app`), satu Activity, UI Jetpack Compose, tanpa pustaka navigasi atau injeksi dependensi.
Tujuannya kode yang kecil dan mudah dibaca: ±2.300 baris Kotlin di delapan berkas.

## Berkas

Semua di `app/src/main/java/com/catatanku/app/`.

| Berkas | Tanggung jawab | Bergantung pada Android? |
|---|---|---|
| `SearchEngine.kt` | Tokenisasi, indeks TF-IDF, pencarian, catatan terkait, kata kunci | Tidak |
| `AiText.kt` | Prompt, pengurai keluaran model, alat teks (centang, hitung kata) | Tidak |
| `Models.kt` | Katalog model: URL terkunci revisi, ukuran, SHA-256, anjuran RAM | Tidak |
| `Data.kt` | `Note`, SQLite (`NoteDb`), cadangan JSON | Ya |
| `LocalAi.kt` | Unduh/impor/ganti model, engine LiteRT-LM, pembangkitan streaming | Ya |
| `AppViewModel.kt` | Seluruh state dan logika aplikasi | Ya |
| `Screens.kt` | Empat layar: daftar, editor, tanya, pengaturan | Ya |
| `Theme.kt` | Warna, tipografi, ikon | Ya |

Tiga berkas pertama sengaja murni Kotlin agar bisa diuji di JVM dan dipakai ulang di luar aplikasi.

## Alur data

```
Screens ──aksi──▶ AppViewModel ──▶ NoteDb (SQLite)
   ▲                  │  ├──▶ SearchIndex (di memori, dibangun ulang tiap data berubah)
   └──── state ───────┘  └──▶ LocalAi ──▶ LiteRT-LM (.litertlm)
```

State disimpan sebagai `mutableStateOf` di ViewModel; layar hanya membaca dan memanggil fungsi.
Navigasi adalah satu enum `Screen`.

## Pencarian

Setiap catatan menjadi vektor TF-IDF dari dua jenis fitur:

- **kata** (tanpa kata umum Indonesia/Inggris)
- **trigram karakter** dari kata ≥ 4 huruf, berbobot 0,3

Judul berbobot 2,5× dan tag 2×. Skor adalah kemiripan kosinus, ditambah bonus bila kueri muncul utuh.
Trigram menggantikan stemmer: "berlari" dan "lari" berbagi `lar`, `ari`.

Agar trigram tidak menimbulkan kecocokan palsu, hasil harus lolos **gerbang relevansi**: minimal satu kata kueri
ada persis, atau minimal dua dan separuh trigramnya ada. Tanpa ini "lari" cocok dengan "Januari".

Indeks dibangun ulang penuh tiap perubahan. Itu sederhana dan cukup untuk ribuan catatan; untuk puluhan ribu,
ganti dengan pembaruan inkremental.

## AI lokal

**Runtime**: LiteRT-LM (`litertlm-android`), pengganti MediaPipe LLM Inference yang kini hanya dipelihara.

**Satu permintaan = satu percakapan baru.** Tidak ada riwayat, sehingga konteks kecil dan hasil konsisten
(suhu 0,3, seed tetap). Semua permintaan diserialkan dengan satu `Mutex`.

**Pembagian kerja** — LLM hanya dipakai untuk hal yang memang butuh bahasa:

| Fitur | Mesin |
|---|---|
| Pencarian, catatan terkait | TF-IDF (instan, tanpa model) |
| Perluas pencarian | LLM menghasilkan kata kunci → TF-IDF mencari |
| Tanya catatan | TF-IDF mengambil 4 catatan → LLM menjawab dari situ |
| Tag, judul, bantu tulis | LLM |

**Keluaran model tidak dipercaya begitu saja.** `AiText` membersihkan Markdown dan blok penalaran, mengurai
format secara longgar, dan menolak folder yang tidak ada di daftar pengguna. Bila model hanya mengisi sebagian,
tag diminta ulang secara terpisah.

**Batas masukan** bergantung model (`ModelSpec.inputChars`): 6.000 karakter untuk Gemma 4, 2.800 untuk model kecil.

### Menambah model

Tambahkan satu `ModelSpec` di `Models.kt`. Ambil revisi, ukuran, dan SHA-256 dari
`https://huggingface.co/api/models/<repo>?blobs=true`, dan pastikan `gated` bernilai `false`.

Model yang "berpikir" dulu (mis. Qwen3) tidak cocok: di pengujian, satu permintaan tag butuh 74 detik.

### Unduhan

`DownloadManager` Android mengunduh ke penyimpanan eksternal milik aplikasi sebagai `.part`. Setelah selesai,
ukuran dan SHA-256 diperiksa, baru berkas diganti nama dan dijadikan model aktif. Status dipantau dengan polling,
dan dilanjutkan bila aplikasi sempat ditutup.

## Penyimpanan

SQLite lewat `SQLiteOpenHelper`, satu tabel `notes`. Tag disimpan sebagai teks berpemisah `U+001F`.
Skema v2 menambah `folder`, `archived`, `deleted` (stempel waktu; 0 = tidak di sampah).

Simpan otomatis ditulis **sinkron** di thread utama. Satu baris hanya beberapa milidetik, dan ini mencegah
catatan ganda bila simpan terpicu dua kali berdekatan.

## Desain

Permukaan netral, tinta sebagai warna utama, dan satu aksen amber yang hanya dipakai untuk AI.
Warna tetap (tidak mengikuti wallpaper) agar konsisten. Ikon digambar sendiri di `Theme.kt` supaya tidak perlu
pustaka ikon besar.

## Pengujian

| Kelas | Isi |
|---|---|
| `SearchEngineTest` | Morfologi, salah ketik, kecocokan palsu, catatan terkait |
| `AiTextTest` | Pengurai keluaran model, pembersih Markdown, tombol centang, batas prompt, katalog |
| `ShotsLight` / `ShotsDark` | Render 10 layar ke PNG |

Uji tangkapan layar menjalankan aplikasi sungguhan di Robolectric dengan basis data nyata dan berkas model tiruan
(model tidak pernah dimuat).
