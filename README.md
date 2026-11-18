<div align="center">

<img src="docs/gambar/ikon.png" width="96" alt="Ikon Catatanku">

# Catatanku

**Catatan pribadi yang bekerja offline, dengan AI yang berjalan sepenuhnya di HP.**

*An offline-first Android notes app with reminders and an on-device AI assistant. No account, no cloud, no tracking.*

[![Versi](https://img.shields.io/badge/versi-1.8.1-191918)](CHANGELOG.md)
[![Android](https://img.shields.io/badge/Android-8.0%2B-191918)](#pasang)
[![Lisensi](https://img.shields.io/badge/lisensi-Apache--2.0-191918)](LICENSE)
[![Offline](https://img.shields.io/badge/data-tetap%20di%20perangkat-8A5200)](#privasi)

### [Unduh Catatanku 1.8.1 (APK, 24,9 MB)](https://github.com/papertop221/Catatanku/releases/download/v1.8.1/Catatanku-1.8.1.apk)

Android 8.0 ke atas · arm64 · [Riwayat perubahan](CHANGELOG.md)

</div>

![Daftar, editor, lembar pengingat, dan rencana asisten dalam mode terang](docs/screenshots/terang.png)
![Tampilan yang sama dalam mode gelap](docs/screenshots/gelap.png)

## Sekilas

| | |
|---|---|
| **Milikmu sendiri** | Tanpa akun dan tanpa server. Catatan, pengingat, dan pemrosesan AI tidak pernah keluar dari HP. |
| **Tidak kelupaan** | Pengingat sekali atau berulang, gaya alarm, mode tahan penghemat baterai, dan blok "Hari ini" untuk yang terlewat dan tugas yang belum selesai. |
| **Cukup diketik** | "ingatkan saya bayar listrik besok jam 7 malam" di kolom cari menjadi catatan berpengingat, setelah kamu setujui. |
| **AI yang membantu, bukan mengambil alih** | Ringkas, perbaiki ejaan, ambil daftar tugas, tanya catatan — juga langsung dari menu teks terpilih di aplikasi lain. Hasil selalu ditinjau dulu dan bisa diurungkan. |
| **Ringan** | APK 24,9 MB. Pencarian instan tanpa model; dekode media dan indeks ditunda hemat RAM untuk HP berspesifikasi rendah; AI hanya memakai RAM saat dipakai. |

**Isi:** [Filosofi](#filosofi) · [Pasang](#pasang) · [Fitur](#fitur) · [Model AI](#model-ai) · [Privasi](#privasi) · [Build](#build) · [Status pengujian](#status-pengujian) · [Dokumentasi](#dokumentasi) · [Lisensi](#lisensi)

## Filosofi

Catatanku dibangun di atas lima pilar yang menjadi standar wajib setiap perubahan kode maupun fitur (lihat [Filosofi & Standar Pengembangan](docs/FILOSOFI.md) dan [AGENTS.md](AGENTS.md)):

- **Praktis:** Buka, tulis, selesai — simpan otomatis tanpa tombol simpan, ketuk langsung kotak centang, selalu bisa diurungkan, dan menyatu dengan menu bagikan, teks terpilih, serta Setelan Cepat Android.
- **Efektif & Efisien:** Tetap responsif dan hemat RAM bahkan di HP berspesifikasi rendah (dekode gambar asinkron/subsampled, cadangan & audio *streaming*, transaksi SQLite, pembaruan indeks ditunda saat mengetik, tanpa dependensi berat).
- **Canggih:** AI lokal (teks, gambar, suara) yang benar-benar membantu dan terintegrasi sistem, bukan gimmick visual; waktu dan pencarian instan tetap dikerjakan kode deterministik.
- **100% Privasi:** Offline-first tanpa akun, server, pelacak, iklan, ataupun SDK tertutup; jaringan hanya untuk mengunduh model AI atas permintaan pengguna.
- **Aman:** Kunci aplikasi ditegakkan hingga ke notifikasi bertoken acak, `FLAG_SECURE`, dan clipboard sensitif; input eksternal (URI, cadangan JSON, prompt AI) disaring ketat dan trafik jaringan tak terenkripsi ditolak.

## Pasang

1. Unduh [`Catatanku-1.8.1.apk`](https://github.com/papertop221/Catatanku/releases/download/v1.8.1/Catatanku-1.8.1.apk).
2. Buka berkasnya di HP dan izinkan pemasangan dari sumber ini bila diminta.
3. Opsional: Pengaturan → Model AI → unduh model untuk mengaktifkan fitur AI.

SHA-256: `4f213685a474101d34ce8cd09d9df0ef4a05300395d732e696d8d4cf0390ec2a`

Play Protect mungkin menampilkan peringatan aplikasi tak dikenal, karena APK ini tidak berasal dari Play Store.

**Memperbarui dari 1.4–1.8:** pasang APK baru langsung di atasnya (atau perbarui lewat F-Droid); catatan, pengingat,
dan model AI tetap. Sebelum memperbarui, sebaiknya ekspor dulu lewat Pengaturan → Cadangan → **Ekspor**.

> [!IMPORTANT]
> Versi 1.8.1 lulus 59 uji unit JVM + 1 benchmark `PerfTest` di Termux (dan membawa 23 uji Robolectric yang terkompilasi untuk desktop Linux `glibc` / CI), serta dibenchmark langsung untuk runtime LiteRT-LM pada Moto G86 Power 5G. Bila menemukan masalah,
> [laporkan di sini](https://github.com/papertop221/Catatanku/issues/new/choose) dengan menyebut merek HP dan versi Android.

<details>
<summary>Pindah dari 1.3 atau lebih lama</summary>

Mulai 1.4 aplikasi ditandatangani dengan kunci baru yang tetap; kunci versi lama tidak tersimpan. Android menolak memasang
versi baru di atas 1.3 atau lebih lama, jadi:

1. Di versi lama: Pengaturan → **Ekspor**, simpan berkas JSON-nya.
2. Copot versi lama. Model AI yang sudah diunduh ikut terhapus.
3. Pasang 1.8.1, lalu Pengaturan → **Impor**.
4. Unduh ulang model AI.

</details>

## Fitur

**Catatan**
- Simpan otomatis tiap jeda mengetik, sematkan, folder (bisa diubah namanya atau dibubarkan), tag, arsip, dan Sampah (pulih dalam 30 hari)
- Daftar centang interaktif (ketuk langsung kotak `☐`/`☑` di editor atau di daftar *Tugas belum selesai*; baris selesai dicoret) dan poin, otomatis berlanjut saat menekan *Enter*
- Riwayat *Urungkan/Ulangi* ketikan di editor + 20 versi terakhir tiap catatan di Riwayat versi
- Catatan suara (rekam WAV 16 kHz mono & putar ulang) dan lampiran gambar (galeri atau kiriman dari aplikasi lain)
- Bagikan/salin, terima teks, gambar (`image/*`), dan suara (`audio/*`) dari aplikasi lain
- Menu teks terpilih di aplikasi lain (browser, obrolan, PDF): simpan langsung sebagai catatan, simpan dengan pengingat, atau ringkas/perbaiki ejaan/ambil daftar tugas dengan AI lokal (dan ganti teks di aplikasi asal bila kolomnya bisa disunting)
- Tile Setelan Cepat "Catatan baru", pintasan tekan-lama ikon aplikasi, pintu masuk asisten perangkat (`ACTION_ASSIST`), dan intent publik (`NEW_NOTE`, `SHOW_REMINDERS`, `SHOW_TASKS`)
- **Templat**: 8 templat bawaan (Notulen rapat, Rencana proyek, Laporan kerja, Jurnal harian, Catatan belajar, Brief klien, Daftar belanja, Evaluasi), templat buatan asisten dari deskripsi singkat, dan *Templat saya* (tersimpan terenkripsi)
- **Format ringan**: subjudul `## `, `**tebal**`, `_miring_`, kutipan `> `, langsung tampil rapi di editor
- **Tautan antarcatatan** `[[judul]]` dengan saran judul saat mengetik, plus daftar *Ditautkan dari*
- Cari & ganti di dalam catatan, gandakan catatan, ekspor satu catatan ke `.md`/`.txt`
- Geser baris ke kanan untuk mengarsipkan, ke kiri untuk pengingat; **tekan lama untuk memilih banyak** lalu sematkan, arsipkan, pindah folder, beri tag, atau hapus sekaligus (bisa diurungkan)
- Blok "Hari ini": pengingat yang terlewat atau jatuh hari ini, dan jumlah tugas yang belum dicentang

**Pengingat**
- Sekali, harian, mingguan, atau bulanan; berbunyi saat aplikasi tertutup dan setelah HP dinyalakan ulang
- Notifikasi biasa, atau gaya alarm yang berdering dengan volume alarm sampai ditanggapi
- Mode tahan penghemat baterai (`setAlarmClock`, bawaan aktif di Oppo, Vivo, Realme, OnePlus, Xiaomi, Huawei, Transsion), deteksi keterlambatan, tombol "Uji pengingat" 2 menit, panduan setelan mulai otomatis per merek HP, dan salin pengingat ke Kalender HP
- "Selesai" dan "Tunda 10 mnt" langsung dari notifikasi; yang belum ditanggapi tampil merah di daftar
- Waktu di isi catatan ("besok jam 7 malam", "tanggal 24 jam 10 pagi") dikenali dan ditawarkan sebagai pengingat, tanpa model

**Asisten perintah** — ketik perintah di kolom cari
- "ingatkan saya bayar listrik besok jam 7 malam", "catat belanja: susu, telur, roti", "tambahkan tugas beli oli ke catatan servis motor",
  "centang beli oli di catatan servis motor", "beri tag kerja ke catatan rapat, lalu sematkan", "arsipkan catatan …"
- Perintah menjadi rencana paling banyak tiga langkah yang ditinjau dulu; sesudah dijalankan bisa diurungkan sekaligus
- Perintah berpola umum dikenali tanpa model; kalimat bebas dirumuskan ulang oleh model bila terpasang (kurang andal, lihat [Arsitektur](docs/ARSITEKTUR.md#asisten-perintah))
- "Hapus" hanya memindah ke Sampah; isi yang diubah asisten tersimpan di Riwayat versi

**Pencarian** — tanpa model, instan
- TF-IDF kata + trigram karakter: "lari" menemukan "berlari", salah ketik ringan ditoleransi
- Catatan terkait muncul otomatis di editor

**AI lokal** — opsional, butuh model
- *Asisten*: lanjutkan, buat judul, koreksi ejaan, susun ulang, nada formal/santai, terjemahkan (Indonesia ↔ Inggris), ringkasan, ekstrak tugas, **rencana dari tujuan** (dengan tenggat sebagai pengingat), **poin penting** (jadwal, keputusan, angka, kontak, tindak lanjut), dan instruksi khusus
- *Pada teks terpilih*: pilih sebagian teks lalu ringkas, koreksi, ubah nada, terjemahkan, atau jelaskan; hasilnya hanya mengganti bagian itu
- *Gambar & suara (Gemma 4 E2B)*: baca teks/isi gambar (OCR), ambil daftar tugas dari gambar, serta transkripsikan atau ambil daftar tugas dari rekaman suara
- *Catatan panjang*: ringkas, daftar tugas, dan perbaiki ejaan memproses seluruh isi bagian demi bagian
- *Merapikan*: isi judul, folder, dan tag yang kosong untuk satu atau semua catatan, atau otomatis saat catatan ditutup
- *Tanyakan*: jawaban dari hingga 4 catatan paling relevan, tampil di atas daftar lengkap dengan sumbernya
- *Ringkas tampilan ini* (folder, tag, hasil cari) dan *Ringkasan mingguan*, bernomor sumber dan bisa disimpan sebagai catatan; daftar *Tugas terbuka*
- *Sarankan pengingat* dari jadwal atau tenggat di catatan
- *Perluas pencarian*: "jogging" → "lari, olahraga"

Hasil AI selalu ditinjau dulu sebelum diterapkan; "Ganti isi" bisa diurungkan, dan merapikan tidak pernah menimpa isian yang sudah ada.

**Tampilan**
- Arah desain "Fokus": judul besar, daftar ringkas, dan dok mengambang (Catatan · Cari · Pengingat · Pengaturan) sejangkauan ibu jari
- Tema Sistem, Terang, atau Gelap (hitam pekat) dengan tombol cepat; warna mengikuti wallpaper di Android 12+
- Aturan desainnya tertulis di [DESAIN.md](docs/DESAIN.md)

**Keamanan dan cadangan**
- Kunci aplikasi dengan sidik jari atau PIN layar HP (`FLAG_SECURE` juga melindungi layar teks terpilih dan salinan papan klip ditandai sensitif di Android 13+)
- Validasi ketat intent notifikasi (`EXTRA_TOKEN`), URI eksternal (menolak `file://` dan otoritas internal), perlindungan *path traversal* & *ZIP bomb*, verifikasi SHA-256 tiap entri, serta pembersih token templat prompt AI
- Ekspor & impor ZIP lengkap (`catatanku-backup.zip`) beserta lampiran gambar/suara dan riwayat versi lewat pemulihan *staging* atomik, kompatibel mundur dengan JSON v1–v3, serta pilihan cadangan terenkripsi AES-256-GCM (PBKDF2WithHmacSHA256)
- Cadangan otomatis streaming harian (`AutoBackup`) ke folder pilihan pengguna (7 arsip ZIP terakhir)

## Model AI

Pengaturan → Model AI → **Unduh**. Unduhan berjalan di latar, lanjut otomatis bila koneksi putus, dan diverifikasi SHA-256.

| Model | Unduhan | RAM saat dipakai* | Catatan |
|---|---|---|---|
| Gemma 4 E2B | 2,6 GB | ±1,45–1,75 GB (`CPU-4`) / ±2,24–2,37 GB (`GPU`) | Pilihan utama untuk semua HP: tercepat, paling hemat RAM, dan mendukung teks, gambar (`GPU vision` ±11,5 dtk), serta suara (`CPU-4`) |
| Qwen3 1.7B | 1,0 GB | ±2,0–2,8 GB | Unduhan terkecil (khusus teks, tanpa baca gambar/suara); tag dan perbaikan ejaan kurang andal |
| Gemma 4 E4B | 3,7 GB | belum diukur | Paling berat; belum diuji |

\* Pengukuran `gemma-4-E2B-it.litertlm` di Moto G86 Power 5G (`mt6878` Dimensity 7300, Android 16) dengan `CPU-4` + profil konteks **Hemat (2.048 token)** & MTP per-tugas; model pembanding Qwen3 1.7B berasal dari pengukuran desktop Linux (CPU). Rinciannya ada di [Benchmark](docs/BENCHMARK.md) dan [Arsitektur](docs/ARSITEKTUR.md).

Untuk HP dengan RAM terbatas (< 7 GB fisik, tidak menghitung swap/RAM Boost), profil **Hemat** (jendela konteks 2.048 token) aktif secara bawaan, dan `MemoryPolicy` melepas model secara adaptif tanpa memicu `System.gc()`.

Semua berlisensi Apache-2.0 dari [litert-community](https://huggingface.co/litert-community) dan bisa diunduh tanpa login.
Sudah punya berkas `.litertlm`? Pakai **Berkas lokal** di Pengaturan → Asisten.

## Privasi

Catatan, pengingat, dan pemrosesan AI tetap di perangkat. Izin internet hanya dipakai untuk mengunduh berkas model AI
lewat `DownloadManager` saat pengguna memintanya. Tidak ada isi catatan atau data pengguna yang dikirim, dan
tidak ada fitur pembaruan otomatis maupun pelacak di latar belakang. Cadangan cloud Android dimatikan.

Aplikasi tetap berfungsi penuh tanpa izin internet (impor berkas model lokal tetap berjalan). Tanpa self-updater
dan izin `REQUEST_INSTALL_PACKAGES`, build resmi siap memenuhi kebijakan F-Droid.

Izin lain: notifikasi, alarm tepat waktu, dan mulai-saat-boot untuk pengingat; biometrik untuk kunci aplikasi;
mikrofon saat merekam catatan suara.

## Build

Butuh JDK 21+ (untuk Robolectric SDK 36; build APK cukup JDK 17+) dan Android SDK 36.

```bash
./gradlew assembleRelease      # APK rilis (R8): app/build/outputs/apk/release/
./gradlew assembleDebug        # APK uji "Catatanku uji" (id .uji), bisa berdampingan dengan rilis resmi
./gradlew testDebugUnitTest    # uji logika + render layar
./gradlew testDebugUnitTest --tests '*Shots*' -Pshots=true   # tangkapan layar → app/build/shots/
./gradlew testDebugUnitTest --tests '*PerfTest*' -Pperf=true -i   # pengukuran mesin pencarian
```

Versi: AGP 8.13.2 · Kotlin 2.4.21 · Compose BOM 2025.10.01 · LiteRT-LM 0.19.1 · minSdk 26.
APK hanya memuat `arm64-v8a`; hapus `abiFilters` di `app/build.gradle.kts` untuk emulator x86_64.

**Kunci tanda tangan.** Build rilis membaca `keystore.properties` di akar proyek (tidak ikut repositori):

```properties
storeFile=/jalur/ke/catatanku-release.jks
storePassword=...
keyAlias=catatanku
keyPassword=...
```

Tanpa berkas itu, build memakai kunci debug mesin setempat, dan hasilnya tidak bisa dipasang di atas rilis resmi.
Simpan berkas `.jks` dan sandinya di tempat aman: bila hilang, pembaruan berikutnya kembali butuh copot-pasang.

## Status pengujian

| Bagian | Status |
|---|---|
| Uji unit JVM murni & benchmark pencarian | 56 uji unit JVM (`AgentTest` 7, `AiTextTest` 17, `AiQualityTest` 4, `BackupArchiveTest` 7, `PerformanceAndMemoryTest` 5, `SearchEngineTest` 9, `TimeParseTest` 7) + 1 `PerfTest` berjalan di Termux |
| Mesin pencarian, pengurai keluaran & kualitas prompt AI, profil performa & `MemoryPolicy`, cadangan ZIP lengkap & enkripsi AES-256-GCM, pengenal waktu, pengurai perintah asisten | Uji unit JVM otomatis |
| Pengingat (`EXTRA_DUE`, pelindung duplikat, perubahan jam & izin exact alarm, mode tahan hemat baterai), riwayat versi, migrasi v2 → v3 → v4 → v5, transaksi impor SQLite | 23 uji Robolectric (`ReminderTest` 20, `Shots*` 3) terkompilasi & dijalankan via Linux `glibc` / CI (`.github/workflows/ci.yml`) |
| Benchmark AI lokal pada perangkat (CPU sweep, GPU, MTP per-tugas, KV cache 2048/3072/4096, vision GPU 1024px, audio CPU, thermal 5-run) | Diukur langsung pada Moto G86 Power 5G (`mt6878`, `gemma-4-E2B-it.litertlm`, `litertlm-android` 0.19.1) — lihat [Benchmark](docs/BENCHMARK.md) |
| Build debug & rilis R8 (`assembleDebug`, `assembleRelease`), Android Lint, audit graf ketergantungan bebas SDK tertutup | Diverifikasi saat build & CI |
| Skenario sentuh interaktif APK penuh, transkripsi suara ucapan bahasa Indonesia nyata, serta keandalan alarm latar belakang pada merek OEM agresif (Oppo/Vivo/Xiaomi/Huawei/Transsion) | **Belum diuji langsung di perangkat OEM masing-masing** |

## Dokumentasi

- [Filosofi dan standar pengembangan](docs/FILOSOFI.md) — lima pilar wajib (Praktis, Efektif & Efisien, Canggih, 100% Privasi, Aman)
- [Benchmark AI Moto G86 Power 5G](docs/BENCHMARK.md) — metodologi & hasil pengukuran CPU thread, GPU, MTP, KV cache, dan multimodal
- [Audit keamanan & privasi](docs/KEAMANAN.md) — kontrol akses, cadangan terenkripsi, dan validasi URI/ZIP
- [Arsitektur](docs/ARSITEKTUR.md) — struktur kode modular, alur data, keputusan teknis
- [Desain](docs/DESAIN.md) — aturan tampilan "Kertas & Tinta"
- [Riwayat perubahan](CHANGELOG.md)
- [Peta jalan dan keputusan](docs/ROADMAP.md)
- [Berkontribusi](CONTRIBUTING.md)
- [Panduan untuk asisten AI](AGENTS.md)

## Lisensi

[Apache License 2.0](LICENSE) © 2026 papertop221.

Model AI tidak disertakan dan diunduh pengguna langsung dari penerbitnya di bawah lisensi masing-masing
(Gemma 4 dan Qwen3: Apache-2.0). Pustaka pihak ketiga: LiteRT-LM, Jetpack Compose, Kotlin (Apache-2.0).
Huruf Atkinson Hyperlegible Next © Braille Institute of America, [SIL Open Font License 1.1](docs/lisensi/AtkinsonHyperlegibleNext-OFL.txt).
