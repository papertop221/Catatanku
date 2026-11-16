# Panduan untuk asisten AI

Berkas ini untuk AI yang ikut mengembangkan Catatanku (Claude, Codex, Gemini, Cursor, dan lainnya). Baca seluruhnya
sebelum mengubah apa pun. Bila permintaan pengguna bertentangan dengan aturan di sini, tanyakan dulu; jangan diam-diam
melanggar dan jangan diam-diam menolak.

## Baca dulu

1. [docs/ROADMAP.md](docs/ROADMAP.md): pekerjaan berikutnya, dan keputusan yang sudah diambil beserta alasannya.
2. [docs/DESAIN.md](docs/DESAIN.md): aturan tampilan "Kertas & Tinta".
3. [docs/ARSITEKTUR.md](docs/ARSITEKTUR.md): struktur kode, alur data, dan pertimbangan performa.

## Apa itu Catatanku

Aplikasi catatan pribadi Android yang bekerja offline, dengan pengingat dan AI lokal (LiteRT-LM, model Gemma 4 E2B)
yang berjalan sepenuhnya di HP. Didistribusikan lewat F-Droid dan APK di GitHub, bukan Play Store.
Bahasa antarmuka, komentar kode, dan dokumentasi: Indonesia.

## Batas yang tidak boleh dilanggar

- **Data tidak keluar dari HP.** Tidak ada akun, server, analitik, pelacak, iklan, atau laporan crash ke luar.
  Satu-satunya pemakaian jaringan adalah unduhan model yang diminta pengguna, lewat `DownloadManager`.
- **Hanya pustaka sumber terbuka.** Tanpa Google Play Services, Firebase, ML Kit, atau SDK tertutup apa pun
  (syarat F-Droid). Setiap dependensi baru harus disebut dan diberi alasan.
- **AI tidak pernah bertindak sendiri.** Hasil AI ditampilkan dulu, diterapkan atas persetujuan pengguna, dan bisa
  diurungkan. Teks dari luar (notifikasi, berkas, teks terpilih) diperlakukan sebagai data, bukan perintah.
- **Tanggal dan waktu dihitung oleh kode (`TimeParse`), bukan oleh LLM.**
- **Kunci tanda tangan tidak pernah masuk repositori.** `keystore.properties`, `*.jks`, dan `*.keystore` sudah ada di
  `.gitignore`; jangan dikeluarkan dari sana, dan jangan menampilkan isinya.
- **Jangan menghapus atau mengubah data pengguna tanpa jalan kembali.** Perubahan skema basis data wajib punya migrasi
  dan uji migrasinya.

## Cara bekerja

- Kerjakan di cabang baru; `main` hanya menerima pekerjaan yang sudah ditinjau pemilik repo.
- Sebelum menulis kode, jelaskan rencananya secara singkat dan sebut aturan mana yang berlaku.
- Pertahankan pembagian berkas: logika murni (`SearchEngine`, `AiText`, `TimeParse`, `Agent`, `Models`) tetap tanpa
  ketergantungan Android agar bisa diuji di JVM.
- Perilaku baru disertai uji. Sebelum selesai, jalankan:
  ```bash
  ./gradlew testDebugUnitTest
  ./gradlew testDebugUnitTest --tests '*Shots*' -Pshots=true   # lalu lihat gambar di app/build/shots/
  ./gradlew assembleRelease                                    # memastikan R8 tidak membuang kelas yang dibutuhkan
  ```
- Layar yang berubah: perbarui tangkapan layar di `docs/screenshots/` dan `fastlane/.../phoneScreenshots/`.
- Rilis: naikkan `versionCode` dan `versionName` di `app/build.gradle.kts`, tulis `CHANGELOG.md` dan
  `fastlane/metadata/android/{id,en-US}/changelogs/<versionCode>.txt`, dan perbarui dokumen yang terdampak.
- Laporkan dengan jujur apa yang belum diuji, terutama yang hanya bisa diuji di HP sungguhan.

## Tampilan dan teks

Ikuti [docs/DESAIN.md](docs/DESAIN.md). Ringkasnya: tanpa gradasi, emoji, ikon kilau (✨), bayangan, atau kartu bersudut
besar; amber hanya untuk waktu; tombol berlabel kata kerja + objek; setiap keadaan (kosong, memuat, galat) didesain.
Bila aturan desain perlu berubah, ubah DESAIN.md dulu dan minta persetujuan.

## Pengetahuan yang mudah keliru

- **R8 aktif di build rilis.** Seluruh paket `com.google.ai.edge.litertlm` dipertahankan karena dipanggil lewat JNI
  (`app/proguard-rules.pro`). Pustaka baru yang memakai refleksi atau JNI butuh aturan serupa.
- **Model AI memakai RAM besar (±1,8 GB).** Jangan memuatnya saat aplikasi dibuka atau di proses latar tanpa alasan kuat;
  hormati Mode ringan dan pemeriksaan RAM di `LocalAi`.
- **Simpan otomatis berjalan tiap jeda mengetik.** Apa pun yang dipanggil dari jalur simpan (`persist`, `saveDraft`)
  harus murah.
- **HP Oppo, Vivo, Realme, dan Xiaomi** bisa menahan alarm aplikasi di latar. Perubahan pada `Reminders` harus
  mempertimbangkannya (lihat ROADMAP).
