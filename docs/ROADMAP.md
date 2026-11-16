# Peta jalan dan keputusan

Diperbarui 16 November 2026, setelah rilis 1.6.

## Keadaan sekarang

- **1.6** ada di `main` dan sudah diterbitkan (APK di `releases/`). 49 uji otomatis lulus.
- Belum ada versi yang diuji menyeluruh di HP sungguhan. Itu prioritas tertinggi sebelum menambah fitur besar.

## Berikutnya: 1.7 (cabang `wip-1.7`, belum selesai)

Kodenya sudah ditulis dan terkompilasi, tetapi **belum diuji di HP** dan **dua uji baru masih gagal**.

| Bagian | Isi | Status |
|---|---|---|
| Menu teks terpilih | "Catatanku" di menu salin/tempel aplikasi lain: simpan, simpan + pengingat, ringkas, perbaiki ejaan, ambil tugas; bisa mengganti teks di aplikasi asal (`ProcessTextActivity`) | Terkompilasi |
| Tile Setelan Cepat | "Catatan baru" (`QuickNoteTile`) | Terkompilasi |
| Pintasan ikon | Catatan baru, Pengingat, Tugas belum selesai (didaftarkan dari kode, `publishShortcuts`) | Terkompilasi |
| Intent publik | `com.catatanku.app.NEW_NOTE`, `SHOW_REMINDERS`, `SHOW_TASKS` (untuk Tasker dan sejenisnya) | Terkompilasi |
| Pengingat tahan penghemat baterai | Semua pengingat dijadwalkan dengan `setAlarmClock` (bawaan nyala di HP Oppo/Vivo/Realme/Xiaomi), deteksi pengingat terlambat, tombol "Uji pengingat" 2 menit, langkah setelan per pabrikan, pengecualian pengoptimalan baterai | Terkompilasi; 2 uji gagal |
| Salin ke Kalender HP | Dari lembar pengingat, lewat layar "acara baru" kalender (tanpa izin kalender) | Terkompilasi |
| Lain-lain | `LocalAi` satu objek per proses; `whenLabel` lebih murah; `localeFilters` id/en | Terkompilasi |

**Yang harus dibereskan sebelum 1.7 digabung:**

1. `ReminderTest.robustModeUsesAlarmClockForEveryReminder` gagal (NullPointerException). Kemungkinan uji membaca
   `alarmClockInfo` yang tidak diisi Robolectric; periksa dulu apakah kodenya benar, baru ujinya.
2. `ReminderTest.lateReminderIsRecordedButPhoneOffIsNot` gagal: pengingat yang terlewat karena HP mati ikut tercatat
   sebagai "terlambat". Periksa cara `Reminders.noteLateness` membedakan HP mati dan aplikasi ditahan
   (`SystemClock.elapsedRealtime`), dan cara uji memajukan jam.
3. Render tangkapan layar Pengaturan (bagian baru "Agar pengingat tidak ditahan HP") dan lembar menu teks terpilih,
   lalu periksa terhadap DESAIN.md.
4. Uji di HP sungguhan, terutama Oppo dan Vivo:
   - Uji pengingat dengan aplikasi ditutup dari layar aplikasi terbaru dan layar terkunci.
   - Tombol "Buka setelan mulai otomatis": nama halaman pabrikan diambil dari daftar komunitas dan bisa berbeda antarversi.
   - Menu teks terpilih di browser dan aplikasi obrolan; pastikan model tidak dimuat dua kali.
5. CHANGELOG, changelog fastlane `8.txt`, README, dan ARSITEKTUR untuk 1.7.

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
