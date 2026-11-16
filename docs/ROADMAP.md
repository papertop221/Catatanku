# Peta jalan dan keputusan

Diperbarui 16 November 2026, untuk penyelesaian cabang `wip-1.7`.

## Keadaan sekarang

- **1.6** ada di `main` dan sudah diterbitkan.
- **1.7** di cabang `wip-1.7` sudah selesai ditulis, kedua uji `ReminderTest` yang sebelumnya gagal sudah diperbaiki, dokumen (`CHANGELOG.md`, `fastlane/.../changelogs/8.txt`, `README.md`, `docs/ARSITEKTUR.md`) dan APK rilis bertanda tangan (`releases/Catatanku-1.7.apk`) sudah dibangun.
- Belum ada versi yang diuji menyeluruh di HP sungguhan. Itu prioritas tertinggi sebelum menambah fitur besar.

## Status 1.7 (cabang `wip-1.7`)

| Bagian | Isi | Status |
|---|---|---|
| Menu teks terpilih | "Catatanku" di menu salin/tempel aplikasi lain: simpan, simpan + pengingat (waktu berwarna amber sesuai `DESAIN.md`), ringkas, perbaiki ejaan, ambil tugas; bisa mengganti teks di aplikasi asal (`ProcessTextActivity`) | Selesai di kode; masuk `ScreenshotTest` (`23-teks-terpilih`) |
| Tile Setelan Cepat | "Catatan baru" (`QuickNoteTile`) | Selesai di kode |
| Pintasan ikon | Catatan baru, Pengingat, Tugas belum selesai (didaftarkan dari kode, `publishShortcuts`) | Selesai di kode |
| Intent publik | `com.catatanku.app.NEW_NOTE`, `SHOW_REMINDERS`, `SHOW_TASKS` (untuk Tasker dan sejenisnya) | Selesai di kode |
| Pengingat tahan penghemat baterai | Semua pengingat dijadwalkan dengan `setAlarmClock` (bawaan nyala di HP Oppo/Vivo/Realme/Xiaomi), deteksi pengingat terlambat, tombol "Uji pengingat" 2 menit, langkah setelan per pabrikan, pengecualian pengoptimalan baterai | Selesai di kode; kedua uji `ReminderTest` diperbaiki |
| Salin ke Kalender HP | Dari lembar pengingat, lewat layar "acara baru" kalender (tanpa izin kalender) | Selesai di kode |
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
6. **Pindahkan APK dari repositori ke GitHub Releases** agar riwayat git tidak membengkak ±25 MB tiap rilis.

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
| Repositori publik, lisensi Apache-2.0 | Pilihan pemilik |
| Satu layar tanpa tab; cari dan tombol baru di bawah | Ramah satu tangan, lebih sedikit layar |
| Amber = waktu, bukan AI; tanpa ikon ✨, gradasi, emoji, kartu berbayang | Menghindari tampilan AI generik; lihat DESAIN.md |
| Tag di daftar ditaruh di bawah | Permintaan pemilik |
| Asisten perintah: rencana paling banyak tiga langkah, disetujui dulu, bisa diurungkan | AI kecil bisa keliru; pengguna tetap memegang kendali |
| Accessibility Service tidak dipakai | Izinnya terlalu luas; berisiko untuk privasi dan peninjauan F-Droid |
| Notification Listener hanya boleh sebagai pilihan opt-in, belum dibuat | Sama: izin luas |
| ML Kit dan Play Services tidak dipakai | Tertutup; tidak boleh di F-Droid |
| Nama kelas tidak disamarkan di build rilis | Sumber terbuka; jejak galat yang terbaca lebih berguna |
