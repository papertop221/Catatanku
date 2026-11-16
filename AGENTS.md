# Panduan untuk asisten AI

Berkas ini untuk seluruh AI yang ikut mengembangkan Catatanku (Claude, Codex, Gemini, Cursor, Copilot, dan lainnya).
Baca berkas ini dan [docs/FILOSOFI.md](docs/FILOSOFI.md) seluruhnya sebelum mengubah apa pun. Bila permintaan
pengguna bertentangan dengan aturan di sini, tanyakan dulu; jangan diam-diam melanggar dan jangan diam-diam menolak.

## Baca dulu

1. **[docs/FILOSOFI.md](docs/FILOSOFI.md)**: **WAJIB** — 5 pilar filosofi program dan daftar periksa wajib sebelum selesai.
2. [docs/ROADMAP.md](docs/ROADMAP.md): pekerjaan berikutnya, dan keputusan yang sudah diambil beserta alasannya.
3. [docs/DESAIN.md](docs/DESAIN.md): aturan tampilan "Kertas & Tinta".
4. [docs/ARSITEKTUR.md](docs/ARSITEKTUR.md): struktur kode, alur data, keamanan, dan pertimbangan performa.

## Apa itu Catatanku

Aplikasi catatan pribadi Android yang bekerja offline dengan pengingat dan AI lokal (LiteRT-LM, model Gemma 4 E2B)
yang berjalan sepenuhnya di HP. Didistribusikan lewat APK di GitHub (F-Droid memerlukan build tanpa updater),
bukan Play Store.
Bahasa antarmuka, komentar kode, dan dokumentasi: Indonesia.

## Filosofi Wajib (5 Pilar)

Setiap fitur, perbaikan bug, refactor, maupun optimasi **wajib** memenuhi kelima pilar berikut secara bersamaan:

1. **Praktis:** Alur sesingkat mungkin tanpa menu/tab berlapis; simpan otomatis tanpa tombol Simpan; selalu ada
   *Urungkan*; ketuk langsung kotak centang; menyatu dengan sistem Android; bahasa Indonesia manusiawi.
2. **Efektif & Efisien:** Wajib ringan di HP spek rendah (RAM kecil, CPU lambat, eMMC lambat). Tanpa bloat atau
   dependensi berat; jalur mengetik (`persist`/`saveDraft`) dilarang memicu rebuild indeks/statistik penuh
   (`deferList`/`flushListIfStale`); media didekode asinkron di IO dengan *subsampling*; cadangan & audio ditulis
   secara *streaming* (`JsonWriter`/`BufferedOutputStream`); operasi SQLite multi-baris dibungkus transaksi; model AI
   hanya dimuat saat dipakai dengan cek RAM & *fallback* GPU → CPU.
3. **Canggih:** AI lokal terintegrasi nyata (ringkas, ejaan, tanya catatan, ekstraksi tugas dua langkah teks/gambar/suara,
   asisten rencana maks 3 langkah), bukan gimmick visual atau chatbot terpisah. Hal presisi/instan (waktu lewat
   `TimeParse`, pencarian lewat `SearchEngine` TF-IDF + trigram) wajib dikerjakan oleh kode deterministik.
4. **100% Privasi:** Nol akun, nol server, nol analitik/pelacak/iklan, nol laporan crash keluar, `allowBackup` mati,
   dan 100% pustaka sumber terbuka. Dua akses jaringan opsional: unduhan model yang diminta sadar oleh pengguna lewat
   `DownloadManager` (dengan SHA-256), dan pemeriksaan/unduhan pembaruan dari GitHub Releases (paling banyak sekali
   sehari, bisa dimatikan, diverifikasi SHA-256 + sertifikat APK). Tetap berfungsi 100% tanpa izin internet. Updater
   membuat build tidak memenuhi kebijakan F-Droid yang melarang self-updater; build F-Droid wajib mematikannya.
5. **Aman:** Kunci aplikasi ditegakkan di semua pintu masuk (`PendingIntent` bertoken `SecureRandom` + ditunda saat
   terkunci, `FLAG_SECURE` di `ProcessTextActivity`, `EXTRA_IS_SENSITIVE` di clipboard API 33+); semua input luar
   dianggap tak tepercaya (`isSafeExternalUri` hanya `content://` non-internal, batas ukuran & pola lampiran impor,
   `AiText.sanitizeInput` cegah *prompt injection*); `networkSecurityConfig` melarang trafik polos (`cleartextTrafficPermitted="false"`).

## Batas yang tidak boleh dilanggar

- **Data tidak keluar dari HP.** Tidak ada akun, server, analitik, pelacak, iklan, atau laporan crash ke luar.
- **Hanya pustaka sumber terbuka.** Tanpa Google Play Services, Firebase, ML Kit, atau SDK tertutup apa pun
  (syarat F-Droid). Setiap dependensi baru harus disebut dan diberi alasan.
- **AI tidak pernah bertindak sendiri.** Hasil AI ditampilkan dulu, diterapkan atas persetujuan pengguna, dan bisa
  diurungkan. Teks dari luar (notifikasi, berkas, teks terpilih, transkrip) diperlakukan sebagai data tak tepercaya
  dan disanitasi sebelum masuk ke prompt.
- **Tanggal dan waktu dihitung oleh kode (`TimeParse`), bukan oleh LLM.**
- **Kunci tanda tangan tidak pernah masuk repositori.** `keystore.properties`, `*.jks`, dan `*.keystore` sudah ada di
  `.gitignore`; jangan dikeluarkan dari sana, dan jangan menampilkan isinya.
- **Jangan menghapus atau mengubah data pengguna tanpa jalan kembali.** Perubahan skema basis data wajib punya migrasi
  dan uji migrasi.
- **Jangan melonggarkan batas keamanan maupun mengorbankan HP spek rendah.** Setiap jalur eksternal wajib menyaring URI,
  ukuran, dan karakter kontrol; UI thread dan jalur ketik harus tetap ringan.

## Cara bekerja

- Kerjakan di cabang baru; `main` hanya menerima pekerjaan yang sudah ditinjau pemilik repo.
- Sebelum menulis kode, jelaskan rencananya secara singkat dan sebut aturan mana dari `AGENTS.md` / `FILOSOFI.md` yang berlaku.
- Pertahankan pembagian berkas: logika murni (`SearchEngine`, `AiText`, `TimeParse`, `Agent`, `Models`) tetap tanpa
  ketergantungan Android agar bisa diuji di JVM.
- Perilaku baru disertai uji. Sebelum selesai, periksa seluruh daftar di [docs/FILOSOFI.md](docs/FILOSOFI.md) dan jalankan:
  ```bash
  ./gradlew testDebugUnitTest
  ./gradlew testDebugUnitTest --tests '*PerfTest*' -Pperf=true -i   # bila menyentuh SearchEngine / indeks / jalur panas
  ./gradlew testDebugUnitTest --tests '*Shots*' -Pshots=true       # lalu lihat gambar di app/build/shots/
  ./gradlew assembleRelease                                        # memastikan R8 tidak membuang kelas yang dibutuhkan
  ```
- Layar yang berubah: perbarui tangkapan layar di `docs/screenshots/` dan `fastlane/.../phoneScreenshots/`.
- Rilis: naikkan `versionCode` dan `versionName` di `app/build.gradle.kts`, tulis `CHANGELOG.md` dan
  `fastlane/metadata/android/{id,en-US}/changelogs/<versionCode>.txt`, dan perbarui dokumen yang terdampak.
  Unggah APK ke GitHub Releases dengan nama **`Catatanku-<versionName>.apk`**; `Updater` hanya menerima aset dengan
  pola nama itu dan digest SHA-256 (GitHub menambahkannya otomatis untuk aset yang diunggah).
- Laporkan dengan jujur apa yang sudah dan belum diuji, terutama yang hanya bisa diuji di HP sungguhan atau tertahan
  keterbatasan lingkungan (mis. Termux Bionic vs Robolectric glibc).

## Tampilan dan teks

Ikuti [docs/DESAIN.md](docs/DESAIN.md). Ringkasnya: tanpa gradasi, emoji, ikon kilau (✨), bayangan, atau kartu bersudut
besar; amber hanya untuk waktu; tombol berlabel kata kerja + objek; setiap keadaan (kosong, memuat, galat) didesain.
Bila aturan desain perlu berubah, ubah DESAIN.md dulu dan minta persetujuan.

## Pengetahuan yang mudah keliru

- **R8 aktif di build rilis.** Seluruh paket `com.google.ai.edge.litertlm` dipertahankan karena dipanggil lewat JNI
  (`app/proguard-rules.pro`). Pustaka baru yang memakai refleksi atau JNI butuh aturan serupa.
- **Model AI memakai RAM besar (±1,8 GB).** Jangan memuatnya saat aplikasi dibuka atau di proses latar tanpa alasan kuat;
  hormati Mode ringan, margin RAM multimodal, pembersihan cache saat hapus, fallback CPU, dan pemeriksaan RAM di `LocalAi`.
- **Simpan otomatis berjalan tiap jeda mengetik.** Apa pun yang dipanggil dari jalur simpan (`persist`, `saveDraft`)
  harus murah; hindari membangun ulang `Stats` / `SearchIndex` sebelum Editor ditutup.
- **HP Oppo, Vivo, Realme, dan Xiaomi** bisa menahan alarm aplikasi di latar. Perubahan pada `Reminders` harus
  mempertimbangkannya (lihat ROADMAP).
