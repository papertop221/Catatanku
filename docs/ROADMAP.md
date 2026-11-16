# Peta jalan dan keputusan

Diperbarui 16 November 2026, mencakup penyelesaian seluruh tahap PRD v1.1 pada cabang `prd-1.8`.

## Keadaan sekarang

- **Panduan wajib aktif:** seluruh pengembangan mengikuti lima pilar di [FILOSOFI.md](FILOSOFI.md) (**Praktis, Efektif & Efisien, Canggih, 100% Privasi, Aman**) dan [AGENTS.md](../AGENTS.md).
- **1.7 & 1.7.1:** APK rilis bertanda tangan di `releases/Catatanku-1.7.apk` (beserta pembaruan 1.7.1 dari GitHub Releases).
- **`prd-1.8`:** Menjalankan benchmark AI lokal langsung di Moto G86 Power 5G (`mt6878` Dimensity 7300, lihat [BENCHMARK.md](BENCHMARK.md)), memperbarui arsitektur menjadi modular (32 berkas Kotlin), menambah cadangan lengkap `catatanku-backup.zip` beserta enkripsi opsional AES-256-GCM, mengoptimalkan profil AI (`CPU-4` + KV 2.048 + MTP per-tugas + GPU vision 1024px) & `MemoryPolicy`, memperkuat keandalan pengingat (`EXTRA_DUE`, perubahan izin exact alarm, perubahan jam), menambah pencarian semantik hibrida (`HybridSearch`), dokumentasi audit [KEAMANAN.md](KEAMANAN.md), serta CI GitHub Actions (`.github/workflows/ci.yml`).
- Pengujian APK interaktif langsung pada HP OEM agresif (Oppo, Vivo, Xiaomi, Huawei, Transsion) untuk alarm latar belakang dan alur sentuh penuh masih perlu diuji pada masing-masing merek perangkat.

## Status tahapan PRD (`prd-1.8`)

| Tahap PRD | Isi utama | Status |
|---|---|---|
| Tahap 0 & FR-03.1 | Instrumentasi metrik per-panggilan (`BenchmarkInfo`, `VmHWM` peak RSS, thermal/baterai via `AndroidPerfMonitor`) & benchmark Moto G86 Power 5G (`mt6878`) | Selesai (`docs/BENCHMARK.md`) |
| Tahap 1 & FR-01 | Cadangan lengkap `catatanku-backup.zip` (`manifest.json`, `notes.json` + versi, lampiran gambar/audio, `checksums.json` SHA-256), *staging* atomik + transaksi SQLite, pembatalan aman, anti-traversal/ZIP bomb, kompatibel JSON v1–v3 | Selesai di kode + uji `BackupArchiveTest` |
| Tahap 2 & FR-03.2–03.5 | Tuning terukur: `CPU-4` default (~1 dtk load, hemat 575–680 MB RAM), GPU + fallback + profil per SoC/SDK/runtime/model, MTP per-tugas via `SpeculativeDecodingMode`, KV cache 2048/3072/4096, `maxOutputToken` per tugas | Selesai & terverifikasi di Moto G86 Power 5G |
| Tahap 3 & FR-03.6–03.9 | `MemoryPolicy` berbasis RAM fisik (tanpa RAM Boost), lazy vision/audio, turunan OCR ≤ 1024px di `cacheDir` + GPU vision, cek kapabilitas NPU & penanda kapabilitas model (`supportsVision`, `supportsAudio`, `textOnly`) | Selesai (`vision-gpu-1024` & `audio-cpu` terukur di HP) |
| Tahap 4 & FR-02, FR-04 | Pengingat tahan error (`EXTRA_DUE`, cegah duplikat, redam late pasca ubah jam, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, fallback kuota OEM), audit keamanan (`docs/KEAMANAN.md`), enkripsi cadangan opsional AES-256-GCM, re-verifikasi TOCTOU `Updater` | Selesai di kode + uji `ReminderTest` / `BackupArchiveTest` |
| Tahap 5 & FR-05, FR-06 | Modularisasi ke subpaket `domain`, `data`, `ai`, `reminders`, `security`, `media`, `ui` (komponen Manifest tetap di root `com.catatanku.app`) + GitHub Actions CI/CD | Selesai (`.github/workflows/ci.yml`) |
| Tahap 6 & FR-07 | Pencarian hibrida lokal (`HybridSearch`: TF-IDF 0,65 + kosinus embedding 0,35), tabel SQLite `embeddings` skema v5 + migrasi v4→v5, pembungkus `EmbeddingEngine` opsional | Selesai di kode + uji `HybridSearchTest` |

## Status 1.7 (cabang `wip-1.7`)

| Bagian | Isi | Status |
|---|---|---|
| Menu teks terpilih | "Catatanku" di menu salin/tempel aplikasi lain: simpan, simpan + pengingat (waktu berwarna amber sesuai `DESAIN.md`), ringkas, perbaiki ejaan, ambil tugas; bisa mengganti teks di aplikasi asal (`ProcessTextActivity`) | Selesai di kode; masuk `ScreenshotTest` (`23-teks-terpilih`) |
| Tile Setelan Cepat | "Catatan baru" (`QuickNoteTile`) | Selesai di kode |
| Pintasan ikon | Catatan baru, Pengingat, Tugas belum selesai (didaftarkan dari kode, `publishShortcuts`) | Selesai di kode |
| Intent publik | `com.catatanku.app.NEW_NOTE`, `SHOW_REMINDERS`, `SHOW_TASKS` (untuk Tasker dan sejenisnya) | Selesai di kode |
| Pengingat tahan penghemat baterai | Semua pengingat dijadwalkan dengan `setAlarmClock` (bawaan nyala di HP Oppo/Vivo/Realme/Xiaomi), deteksi pengingat terlambat, tombol "Uji pengingat" 2 menit, langkah setelan per pabrikan, pengecualian pengoptimalan baterai | Selesai & diuji di HP |
| Salin ke Kalender HP | Dari lembar pengingat, lewat layar "acara baru" kalender (tanpa izin kalender) | Selesai & diuji di HP |
| Perbaikan fundamental editor & daftar | Ketuk langsung kotak `☐`/`☑` + coret baris selesai, otomatis lanjut daftar saat *Enter*, centang langsung di daftar *Tugas belum selesai*, *Undo/Redo* ketikan, fokus judul→isi, kelola folder (ubah nama/bubarkan), pindah folder & hapus permanen dari menu tekan-lama | Selesai |
| Catatan suara, gambar, & AI multimodal | Rekam suara WAV 16 kHz mono + putar ulang, lampirkan gambar (galeri/Share), skema DB v4 + migrasi, baca gambar (OCR/tugas) & transkripsi suara dengan Gemma 4 E2B, satu kalimat jadi pengingat + daftar centang | Selesai |
| Lain-lain | `LocalAi` satu objek per proses; `whenLabel` lebih murah; `localeFilters` id/en; APK rilis 1.7 (`releases/Catatanku-1.7.apk`) dan dokumen 1.7 | Selesai |

**Catatan pengujian 1.7:**

1. `ReminderTest.robustModeUsesAlarmClockForEveryReminder` diperbaiki dengan mengaktifkan `ShadowAlarmManager.setCanScheduleExactAlarms(true)` di `ReminderTest.setUp()` (di Robolectric SDK 36 nilainya bawaan `false`, padahal di Android nyata izin `USE_EXACT_ALARM` aktif saat dipasang) dan membersihkan `SharedPreferences("ui")` antar-uji.
2. `ReminderTest.lateReminderIsRecordedButPhoneOffIsNot` diperbaiki dengan menghapus pemanggilan `SystemClock.setCurrentTimeMillis(System.currentTimeMillis())` yang di Robolectric mengubah jam monotonik `elapsedRealtime()` menjadi puluhan tahun epoch, sehingga cukup `ShadowSystemClock.advanceBy(Duration.ofHours(2))`.
3. Di lingkungan Termux (Android Bionic), 35 uji unit JVM murni (`AiTextTest`, `SearchEngineTest`, `TimeParseTest`, `AgentTest`) serta `assembleRelease` (R8) lulus; eksekusi runtime Robolectric (`ReminderTest`, `ScreenshotTest`) membutuhkan lingkungan Linux desktop (`glibc`) karena `librobolectric-nativeruntime.so` ditautkan ke `glibc`.
4. Yang masih perlu diuji di HP sungguhan (terutama Oppo dan Vivo):
   - Uji pengingat dengan aplikasi ditutup dari layar aplikasi terbaru dan layar terkunci.
   - Tombol "Buka setelan mulai otomatis": nama halaman pabrikan diambil dari daftar komunitas dan bisa berbeda antarversi.
   - Menu teks terpilih di browser dan aplikasi obrolan; pastikan model tidak dimuat dua kali.

## Sesudah itu (belum dikerjakan, urutan usulan)

1. **Uji HP sungguhan dan perbaikan** dari laporan pengguna.
2. **Baseline Profile** dan pengukuran cold start dengan Macrobenchmark (butuh HP atau emulator).
3. **Satu kalimat menjadi beberapa objek**: "besok jam 7 bayar listrik 250rb" → pengingat + tugas, lewat asisten perintah yang sudah ada.
4. **Catatan suara** dengan transkripsi di perangkat (whisper.cpp atau Vosk; keduanya sumber terbuka).
5. **Pencarian semantik** dengan model embedding kecil, sebagai pelengkap TF-IDF, bukan pengganti.
6. **Pindahkan APK dari repositori ke GitHub Releases** agar riwayat git tidak membengkak ±25 MB tiap rilis (1.7.1 sudah hanya di GitHub Releases; `releases/Catatanku-1.7.apk` lama dibiarkan).

## Keputusan yang sudah diambil

Jangan diusulkan ulang tanpa alasan baru.

| Keputusan | Alasan |
|---|---|
| Android native, Kotlin + Compose, satu Activity, tanpa pustaka navigasi atau injeksi dependensi | Kode kecil dan mudah dibaca |
| LiteRT-LM dengan Gemma 4 E2B sebagai model utama | Pada pengukuran, paling hemat RAM dan paling baik untuk prompt aplikasi ini |
| Model "berpikir" (mis. Qwen3 bawaan) tidak dipakai untuk aksi cepat | Satu permintaan tag butuh 74 detik |
| Mesin pencarian sendiri (TF-IDF + trigram), bukan Room/FTS4 | Toleran imbuhan dan salah ketik bahasa Indonesia ("lari" menemukan "berlari") |
| SQLite lewat `SQLiteOpenHelper`, bukan Room | Tanpa pembangkit kode; skema kecil |
| Distribusi F-Droid dan GitHub, bukan Play Store | Pilihan pemilik; menuntut semua pustaka sumber terbuka |
| Pembaruan mandiri dari GitHub Releases (unduh + pasang, cek otomatis sekali sehari yang bisa dimatikan) | Diminta pemilik. APK diverifikasi SHA-256 rilis + sertifikat tanda tangan. Konsekuensinya build ini **tidak memenuhi kebijakan F-Droid** yang melarang self-updater; build F-Droid harus mematikan `Updater` |
| Repositori publik, lisensi Apache-2.0 | Pilihan pemilik |
| Satu layar tanpa tab; cari dan tombol baru di bawah | Ramah satu tangan, lebih sedikit layar |
| Amber = waktu, bukan AI; tanpa ikon ✨, gradasi, emoji, kartu berbayang | Menghindari tampilan AI generik; lihat DESAIN.md |
| Tag di daftar ditaruh di bawah | Permintaan pemilik |
| Asisten perintah: rencana paling banyak tiga langkah, disetujui dulu, bisa diurungkan | AI kecil bisa keliru; pengguna tetap memegang kendali |
| Accessibility Service tidak dipakai | Izinnya terlalu luas; berisiko untuk privasi dan peninjauan F-Droid |
| Notification Listener hanya boleh sebagai pilihan opt-in, belum dibuat | Sama: izin luas |
| ML Kit dan Play Services tidak dipakai | Tertutup; tidak boleh di F-Droid |
| Nama kelas tidak disamarkan di build rilis | Sumber terbuka; jejak galat yang terbaca lebih berguna |
