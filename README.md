# Catatanku

Aplikasi catatan pribadi Android yang bekerja offline, dengan asisten AI yang berjalan sepenuhnya di perangkat.

**[⬇ Unduh APK v1.4](https://github.com/papertop221/Catatanku/raw/main/releases/Catatanku-1.4.apk)** · 32 MB · Android 8.0+ · arm64

> Kode sumber di cabang ini sudah versi 1.5 (asisten perintah, tag di bawah). APK 1.5 yang ditandatangani belum diterbitkan;
> tautan di atas masih 1.4.

![Tampilan terang](docs/screenshots/terang.png)
![Tampilan gelap](docs/screenshots/gelap.png)

## Pasang

1. Unduh [`Catatanku-1.4.apk`](https://github.com/papertop221/Catatanku/raw/main/releases/Catatanku-1.4.apk).
2. Buka berkasnya di HP dan izinkan pemasangan dari sumber ini bila diminta.
3. Opsional: Pengaturan → Model AI → unduh model untuk mengaktifkan fitur AI.

SHA-256: `6ce7f72a4326d350d598c1709216dcdf5899bb8e49c94a681548dd38ca7f952b`

Play Protect mungkin menampilkan peringatan aplikasi tak dikenal, karena APK ini tidak berasal dari Play Store.

### Pindah dari 1.3 atau lebih lama

Versi 1.4 ditandatangani dengan kunci baru yang tetap; kunci versi lama tidak tersimpan. Android menolak memasang 1.4
di atas versi lama, jadi:

1. Di versi lama: Pengaturan → **Ekspor**, simpan berkas JSON-nya.
2. Copot versi lama. Model AI yang sudah diunduh ikut terhapus.
3. Pasang 1.4, lalu Pengaturan → **Impor**.
4. Unduh ulang model AI.

Mulai 1.4, pembaruan berikutnya bisa dipasang langsung selama dibangun dengan kunci yang sama (lihat [Build](#build)).

## Fitur

**Catatan**
- Simpan otomatis, sematkan, folder, tag, arsip, dan sampah (pulih dalam 30 hari)
- Daftar centang dan poin, hitung kata, urutkan
- Riwayat versi: 20 versi terakhir tiap catatan bisa dipulihkan
- Bagikan/salin, terima teks dari aplikasi lain
- Tekan lama kartu untuk aksi cepat

**Pengingat**
- Sekali, harian, mingguan, atau bulanan; berbunyi saat aplikasi tertutup dan setelah HP dinyalakan ulang
- Notifikasi biasa, atau gaya alarm yang berdering dengan volume alarm sampai ditanggapi
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
- *Bantu tulis*: lanjutkan, perbaiki ejaan, rapikan struktur, ringkas, ambil daftar tugas, buat judul, instruksi bebas
- *Catatan panjang*: ringkas, daftar tugas, dan perbaiki ejaan memproses seluruh isi bagian demi bagian
- *Merapikan*: isi judul, folder, dan tag yang kosong untuk satu atau semua catatan, atau otomatis saat catatan ditutup
- *Tanya catatan*: jawaban dari hingga 4 catatan paling relevan, lengkap dengan sumbernya
- *Rekap 7 hari* dan daftar semua tugas yang belum dicentang
- *Sarankan pengingat* dari jadwal atau tenggat di catatan
- *Perluas pencarian*: "jogging" → "lari, olahraga"

Hasil AI selalu ditinjau dulu sebelum diterapkan; "Ganti isi" bisa diurungkan, dan merapikan tidak pernah menimpa isian yang sudah ada.

**Keamanan dan cadangan**
- Kunci aplikasi dengan sidik jari atau PIN layar HP
- Cadangan otomatis ke folder pilihan (7 berkas terakhir), ditambah ekspor/impor JSON manual
- Cadangan memuat semua catatan, Sampah, dan pengingat. Berkasnya tidak dienkripsi.

## Model AI

Pengaturan → Model AI → **Unduh**. Unduhan berjalan di latar, lanjut otomatis bila koneksi putus, dan diverifikasi SHA-256.

| Model | Unduhan | RAM saat dipakai* | Catatan |
|---|---|---|---|
| Gemma 4 E2B | 2,6 GB | ±1,8 GB | Pilihan utama untuk semua HP: tercepat dan paling hemat RAM; semua fitur diuji dengan model ini |
| Qwen3 1.7B | 1,0 GB | ±2,0–2,8 GB | Unduhan terkecil; tag dan perbaikan ejaan kurang andal, lebih lambat |
| Gemma 4 E4B | 3,7 GB | belum diukur | Paling berat; belum diuji |

\* Puncak memori proses di desktop Linux (CPU), 15 Nov 2026. Belum diukur di HP. Rinciannya ada di [Arsitektur](docs/ARSITEKTUR.md).

Model berukuran berkas lebih kecil belum tentu lebih ringan: pada pengukuran, Gemma 4 E2B memakai RAM paling sedikit.
Untuk HP dengan RAM terbatas, nyalakan **Mode ringan** (aktif otomatis bila RAM di bawah 7 GB): jendela konteks lebih
kecil dan model langsung dilepas dari memori, dengan konsekuensi AI membaca teks lebih pendek.

Semua berlisensi Apache-2.0 dari [litert-community](https://huggingface.co/litert-community) dan bisa diunduh tanpa login.
Sudah punya berkas `.litertlm`? Pakai **Dari berkas**.

## Privasi

Catatan, pengingat, dan pemrosesan AI tetap di perangkat. Izin internet hanya dipakai untuk mengunduh berkas model saat
pengguna memintanya, lewat `DownloadManager` Android; kode aplikasi tidak membuka koneksi sendiri. Cadangan cloud
Android dimatikan. Untuk varian tanpa izin internet sama sekali, hapus `uses-permission INTERNET` di manifest —
impor model dari berkas tetap berfungsi.

Izin lain: notifikasi, alarm tepat waktu, dan mulai-saat-boot untuk pengingat; biometrik untuk kunci aplikasi.

## Build

Butuh JDK 17+ dan Android SDK 36.

```bash
./gradlew assembleRelease      # APK: app/build/outputs/apk/release/
./gradlew assembleDebug        # APK uji "Catatanku uji" (id .uji), bisa berdampingan dengan rilis resmi
./gradlew testDebugUnitTest    # uji logika + render layar
./gradlew testDebugUnitTest --tests '*Shots*' -Pshots=true   # tangkapan layar → app/build/shots/
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
| Mesin pencarian, pengurai keluaran AI, katalog model, pengenal waktu, pemotongan catatan panjang, pengurai perintah asisten | Uji unit otomatis |
| Pengingat (alarm, notifikasi, tunda, selesai, boot), riwayat versi, cadangan, migrasi v2 → v3 | Uji otomatis di Android tiruan (Robolectric) |
| Asisten perintah: rencana, jalankan, urungkan, riwayat versi | Uji otomatis di Android tiruan (Robolectric) |
| Tampilan 22 layar, terang & gelap | Dirender otomatis (Robolectric + Roborazzi) |
| Prompt AI dengan Gemma 4 E2B, Qwen3 1.7B, Qwen2.5 1.5B | Diuji di desktop lewat `litertlm-jvm` |
| Prompt asisten perintah | Diuji di desktop dengan Gemma 4 E2B saja: 15 dari 19 kalimat bebas benar |
| Aplikasi di HP sungguhan (sentuhan, bunyi alarm, sidik jari, unduhan, kecepatan, RAM) | **Belum diuji** |

## Dokumentasi

- [Arsitektur](docs/ARSITEKTUR.md) — struktur kode, alur data, keputusan desain
- [Riwayat perubahan](CHANGELOG.md)

## Lisensi

[Apache License 2.0](LICENSE) © 2026 papertop221.

Model AI tidak disertakan dan diunduh pengguna langsung dari penerbitnya di bawah lisensi masing-masing
(Gemma 4 dan Qwen2.5: Apache-2.0). Pustaka pihak ketiga: LiteRT-LM, Jetpack Compose, Kotlin (Apache-2.0).
