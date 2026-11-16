# Riwayat perubahan

## 1.8

Cadangan lengkap & keamanan data
- Ekspor & impor ZIP lengkap (`catatanku-backup.zip`): memuat `manifest.json`, `notes.json` beserta riwayat versi, lampiran gambar (`attachments/image/`) dan suara (`attachments/audio/`), serta `checksums.json` SHA-256
- Pemulihan atomik melalui direktori *staging* + satu transaksi SQLite (`NoteDb.importInTransaction`) dengan dialog progres, dukungan batal aman, perlindungan *path traversal* & *ZIP bomb*, serta deteksi lampiran hilang
- Cadangan terenkripsi opsional menggunakan AES-256-GCM streaming 64 KB + PBKDF2WithHmacSHA256 (120.000 iterasi) lewat dialog kata sandi saat ekspor/impor (`BackupCrypto`)
- Cadangan otomatis harian ikut menulis arsip ZIP dan kompatibel mundur saat mengimpor JSON lama (v1–v3)
- Verifikasi ulang TOCTOU (ukuran, SHA-256, dan tanda tangan APK) tepat sebelum `PackageInstaller.commit` di `Updater`, validasi URI eksternal terpusat (`InputSecurity`), serta dokumentasi audit keamanan di `docs/KEAMANAN.md`

Keandalan pengingat Android
- Pelindung duplikat notifikasi (`remind_fired` & pemeriksaan `EXTRA_DUE` terhadap jadwal aktif), peredaman deteksi terlambat sesaat setelah perubahan jam/zona waktu sistem, penerimaan `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, dan cadangan `setAndAllowWhileIdle` bila ROM melempar batas kuota alarm

Performa & kecerdasan AI terukur (`docs/BENCHMARK.md`)
- Instrumentasi metrik per-panggilan via `ExperimentalFlags.enableBenchmark` (`BenchmarkInfo`, TTFT, decode tok/s, `VmHWM` peak RSS, status termal & suhu baterai via `AndroidPerfMonitor`) serta tombol **Uji kinerja AI** & panel metrik di Pengaturan
- Profil terukur Moto G86 Power 5G (`mt6878`): `CPU-4` + jendela konteks **Hemat (2.048 token)** + kebijakan MTP per-tugas (`ADAPTIVE` untuk Ringkas/Ejaan/Rapikan/Daftar tugas/Pengingat; `OFF` untuk Judul/Tag/Lanjutkan/Perluas pencarian), menghemat ~575–680 MB RAM dibanding GPU dan memuat model dalam ~1 detik
- Akselerasi vision GPU opsional untuk OCR gambar memakai turunan ≤ 1.024 px di `cacheDir` (memangkas durasi OCR ~26 detik menjadi ~11,5 detik dan menghemat ~452 MB RSS tanpa mengubah gambar asli)
- Kebijakan memori adaptif (`MemoryPolicy`) berbasis RAM fisik (tanpa menghitung swap/RAM Boost), `availMem`, `lowMemory`, status foreground/background, dan `onTrimMemory`, tanpa pemanggilan `System.gc()` agresif
- Penanda kapabilitas model (`supportsVision`, `supportsAudio`, `textOnly`, `isEmbedding`) agar model khusus teks tidak menyembunyikan keterbatasan multimodal secara diam-diam

Pencarian semantik lokal & arsitektur
- Pencarian hibrida (`HybridSearch`): menggabungkan skor TF-IDF + trigram (`0,65`) dengan kosinus embedding lokal (`0,35`), tabel SQLite `embeddings` (skema v5 + migrasi v4→v5), dan jatuh kembali ke TF-IDF bila model embedding belum diimpor
- Modularisasi bertahap kode ke subpaket `domain`, `data`, `ai`, `reminders`, `security`, `media`, dan `ui` serta alur kerja CI GitHub Actions (`.github/workflows/ci.yml`)

## 1.7.1

Pembaruan dari GitHub
- Pengaturan → Pembaruan memeriksa rilis terbaru di repositori resmi GitHub, menampilkan catatan rilis, lalu
  mengunduh dan memasang APK tanpa keluar dari aplikasi
- Pemeriksaan otomatis berjalan paling banyak sekali sehari saat aplikasi dibuka dan bisa dimatikan; tombol
  Periksa pembaruan selalu tersedia
- Hanya aset `Catatanku-<versi>.apk` resmi yang diterima. Sebelum dipasang, ukuran dan SHA-256 dari rilis, nama paket,
  nomor versi, serta sertifikat tanda tangan APK diperiksa agar hanya build resmi yang dapat menggantikan aplikasi
- Build uji tidak dapat memasang pembaruan, dan aplikasi tetap berfungsi penuh tanpa izin internet

## 1.7
Catatan, editor, dan daftar (perbaikan fundamental)
- Daftar centang interaktif: ketuk langsung kotak `☐` atau `☑` di editor untuk mencentang/melepas centang tanpa menghapus
  kotaknya; baris yang sudah `☑` tampil redup dan dicoret
- Menekan *Enter* di baris daftar (`☐`, `☑`, `•`) otomatis melanjutkan daftar di baris baru, atau keluar dari daftar bila
  ditekan pada baris daftar kosong
- Daftar "Tugas yang belum selesai" di layar utama kini interaktif: ketuk langsung butir tugas untuk mencentangnya
  (lengkap dengan tombol Urungkan)
- Tombol *Urungkan* dan *Ulangi* di bilah atas editor untuk suntingan ketikan; tombol *Next* di Judul langsung
  memindahkan fokus ke isi catatan; input tag yang belum ditekan *Done* tetap tersimpan saat keluar dari editor
- Kelola folder: ubah nama folder atau bubarkan folder dari menu titik tiga saat membuka folder; menu tekan-lama di
  daftar kini punya *Pindahkan ke folder*, *Tandai pengingat selesai*, dan *Hapus permanen* (di Sampah)

Catatan suara, gambar, dan AI multimodal
- Rekam catatan suara (WAV 16 kHz mono) dan lampirkan gambar di dalam catatan (`Media.kt`, skema basis data v4);
  berkas audio kiriman aplikasi lain (`.m4a`/`.mp3`/`.ogg`) didekode otomatis ke WAV 16 kHz agar terbaca model
- AI multimodal lokal (Gemma 4 E2B): baca gambar, serta transkripsikan rekaman suara
- Ekstraksi tugas dua langkah dari gambar & suara (`AiText.imageOcrPrompt`, `audioTranscribePrompt`, `extractDirectTasksAndTime`,
  `tasksFromTextPrompt`): foto biasa tanpa teks tidak dikarang menjadi tugas, kalimat perintah dipecah menjadi daftar
  ceklis `☐` yang bersih, dan keterangan waktu ("nanti sore", "sore nanti", "besok jam 10") ditawarkan langsung
  sebagai pengingat di lembar hasil AI (lengkap dengan Urungkan)
- Satu kalimat menjadi beberapa objek: "ingatkan saya besok jam 7 malam rapat RT: siapkan laporan, beli konsumsi #rt di
  folder Warga" langsung membuat catatan berjudul, berisi daftar centang `☐`, bertag, berfolder, sekaligus berpengingat

Integrasi sistem
- "Catatanku" di menu teks terpilih aplikasi lain (browser, obrolan, PDF): simpan langsung sebagai catatan, simpan
  dengan pengingat bila teks memuat waktu, atau olah dengan AI lokal (ringkas, perbaiki ejaan, ambil daftar tugas) dan
  ganti teks di aplikasi asal bila kolomnya bisa disunting
- Terima bagikan (*Share*) gambar (`image/*`) dan berkas suara (`audio/*`) dari aplikasi lain, serta pintu masuk
  asisten perangkat (`ACTION_ASSIST` / `VOICE_COMMAND`)
- Tile Setelan Cepat "Catatan baru" untuk menulis dari mana saja
- Pintasan tekan-lama ikon aplikasi: Catatan baru, Pengingat, dan Tugas belum selesai
- Intent publik `com.catatanku.app.NEW_NOTE`, `SHOW_REMINDERS`, dan `SHOW_TASKS` untuk aplikasi otomasi (mis. Tasker)

Pengingat tahan penghemat baterai
- Jadwalkan sebagai alarm sistem (`setAlarmClock`) untuk semua pengingat, jenis alarm yang paling jarang ditahan
  penghemat baterai; aktif bawaan di HP Oppo, Vivo, Realme, OnePlus, Xiaomi, Huawei, dan Transsion
- Deteksi pengingat terlambat: bila pengingat berbunyi jauh setelah waktunya saat HP menyala, Pengaturan memberi
  tahu dan mengarahkan langkah perbaikannya (yang terlewat karena HP mati tidak dihitung)
- Tombol "Uji pengingat" 2 menit di Pengaturan untuk memastikan pengingat berbunyi saat aplikasi ditutup dan layar terkunci
- Panduan setelan latar belakang per merek HP, tombol pembuka halaman mulai otomatis pabrikan, dan permintaan
  pengecualian pengoptimalan baterai Android
- "Salin juga ke Kalender HP" di lembar pengingat sebagai cadangan tanpa meminta izin kalender

Keamanan
- Kunci aplikasi & intent masuk: saat kunci aktif, intent dari luar (`ACTION_SEND`, `EXTRA_OPEN`, pintasan, asisten) ditunda
  sampai autentikasi berhasil, dan masa tenggang dicek sebelum `onNewIntent` memproses aksi apa pun
- Pelindung notifikasi `EXTRA_OPEN`: token acak 128-bit per pemasangan (`SecureRandom`, dibandingkan dengan
  `MessageDigest.isEqual`) mencegah aplikasi lain menandai pengingat selesai atau membuka catatan lewat intent tiruan
- Tolak `file://` dan otoritas internal aplikasi pada semua pintu masuk URI eksternal (`ACTION_SEND`, impor gambar/suara,
  impor cadangan JSON, impor model AI)
- Batas baca impor cadangan 32 MB, sanitasi ketat nama lampiran (`img_*`/`aud_*`) dan panjang kolom JSON di `Backup.fromJson`,
  validasi header WAV (`8–192 kHz`, `1–8` kanal) dan batas waktu 10 detik saat transkode audio
- Perlindungan layar & papan klip saat kunci aktif: `FLAG_SECURE` di `ProcessTextActivity`, `ClipDescription.EXTRA_IS_SENSITIVE`
  pada penyalinan (Android 13+), dan batas panjang teks masuk (`100.000` karakter)
- Pengaman prompt AI (`AiText.sanitizeInput`): membuang byte nol dan token kendali templat percakapan (`<start_of_turn>`,
  `<|im_start|>`, `<think>`, dll.), serta konfigurasi eksplisit `network_security_config.xml` (menolak lalu lintas cleartext)

Performa & HP spek rendah
- Menulis di Editor jauh lebih ringan: selama di Editor, simpan otomatis tidak membangun ulang `Stats` dan indeks pencarian
  setiap jeda 0,7 detik; pembaruan daftar/indeks ditunda sampai keluar dari Editor atau berganti catatan
- Dekode gambar lampiran secara asinkron di thread IO (`ImageDecoder` di Android 9+, `BitmapFactory.inSampleSize` +
  `RGB_565` untuk pratinjau daftar 720px), serta hitung durasi audio di thread IO agar scroll dan pembukaan Editor tidak patah
- Ekspor & cadangan otomatis memakai `JsonWriter` streaming (`O(1)` memori, tanpa menampung seluruh JSON sebagai string),
  dan transkode audio menulis langsung ke berkas WAV tanpa buffer `ByteArrayOutputStream` raksasa di RAM
- Pemindahan/pembubaran folder kini dijalankan dalam satu transaksi SQLite di `Dispatchers.IO` (`NoteDb.moveFolder`)
- Model AI multimodal menyertakan cadangan tambahan +200 MB dalam pemeriksaan kecukupan RAM, langsung jatuh ke CPU bila
  inisialisasi GPU gagal di perangkat lama, membersihkan cache native/XNNPACK saat model dihapus, dan memanggil `System.gc()`
  sebelum memuat model pada mode ringan

Lain-lain
- `LocalAi` menjadi satu objek per proses agar layar utama dan lembar teks terpilih tidak memuat model dua kali
- Pemformat waktu (`whenLabel`) memakai `DateTimeFormatter` bersama tanpa membuat `SimpleDateFormat` tiap baris;
  `NoteRow` menyimpannya lewat `remember`; filter bahasa sumber daya (`id`, `en`)

## 1.6
Tampilan baru "Kertas & Tinta" (aturannya di docs/DESAIN.md)
- Satu layar utama: blok "Hari ini" (pengingat yang terlewat atau jatuh hari ini, jumlah tugas belum dicentang), lalu daftar
- Kolom cari dan tombol catatan baru pindah ke bawah layar, sejangkauan ibu jari
- Daftar berupa baris datar bergaris, tanpa kartu; geser kanan untuk mengarsipkan, geser kiri untuk pengingat
- Jawaban "Tanya catatan", rekap, dan daftar tugas tampil di atas daftar; layar Tanya tersendiri dihapus
- Editor: penanda "Tersimpan", simpan otomatis tiap jeda mengetik, alat berikon dan berlabel di atas papan ketik
- Amber kini berarti waktu (pengingat), bukan AI; AI tidak lagi punya warna atau ikon khusus
- Isi catatan memakai huruf Atkinson Hyperlegible Next; mode gelap berlatar hitam pekat
- Arsip dan Sampah dari daftar bisa diurungkan; tombol diberi nama sesuai tindakannya
- Pilihan warna mengikuti wallpaper (Android 12+)

Lebih ringan
- APK 25,2 MB (sebelumnya 32,0 MB): kode yang tidak dipakai dibuang saat build (R8)
- Mengetik tidak lagi membangun ulang indeks pencarian: satu simpan menghitung ulang satu catatan, di thread latar
- Pencarian berjalan di thread latar dan hanya menyentuh catatan yang memuat kata kuncinya
- Daftar tidak lagi dibaca ulang dari penyimpanan tiap simpan; tag, folder, dan jumlah dihitung sekali per perubahan
- Simpan otomatis lebih murah (WAL), dan alarm hanya disetel ulang bila pengingatnya berubah
- Model AI dilepas dari RAM begitu aplikasi ditinggalkan atau sistem kekurangan memori, dan tidak dimuat bila RAM
  yang tersedia tidak cukup

## 1.5
Asisten perintah
- Kolom cari kini juga menerima perintah: "ingatkan saya bayar listrik besok jam 7 malam", "catat belanja: susu, telur, roti",
  "tambahkan tugas beli oli ke catatan servis motor", "centang beli oli di catatan servis motor", "beri tag kerja ke catatan
  rapat, lalu sematkan", "arsipkan/hapus catatan …"
- Perintah diubah menjadi rencana paling banyak tiga langkah yang ditampilkan dulu; tidak ada yang berjalan sebelum disetujui
- Catatan sasaran dicari otomatis; bila beberapa cocok, pilih sendiri di rencana
- Setelah dijalankan, semuanya bisa diurungkan sekaligus; isi lama juga masuk Riwayat versi; "hapus" hanya memindah ke Sampah
- Perintah berpola umum dikenali tanpa model (instan). Kalimat bebas dirumuskan ulang oleh model, bila terpasang
- Pengulangan dan gaya alarm dikenali dari kalimat: "tiap hari", "setiap Senin", "setiap tanggal 1", "pasang alarm …"

Tampilan
- Baris tag di daftar pindah ke bawah layar, sehingga tidak lagi mendorong catatan turun dan mudah dijangkau jempol

Lain-lain
- Build uji (`assembleDebug`) memakai id `com.catatanku.app.uji`, jadi bisa dipasang berdampingan dengan rilis resmi

## 1.4
**Penting:** APK ini ditandatangani dengan kunci baru yang tetap, karena kunci 1.0–1.3 tidak tersimpan. Android menolak
memasangnya di atas 1.3. Ekspor catatan di 1.3, copot 1.3, pasang 1.4, lalu impor. Model AI perlu diunduh ulang.

Pengingat
- Pengingat per catatan: sekali, harian, mingguan, atau bulanan; berbunyi saat aplikasi tertutup dan setelah HP dinyalakan ulang
- Dua gaya: notifikasi biasa, atau gaya alarm yang berdering dengan volume alarm sampai ditanggapi
- Tombol "Selesai" dan "Tunda 10 mnt" di notifikasi; pengingat yang belum ditanggapi tampil merah di daftar
- Tampilan "Pengingat" di pemilih tampilan
- Waktu di isi catatan ("besok jam 7 malam", "tanggal 24 jam 10 pagi", "lusa habis magrib") dikenali tanpa model dan ditawarkan sebagai pengingat

AI
- Mode ringan: jendela konteks lebih kecil dan model langsung dilepas dari RAM; aktif otomatis di HP ber-RAM di bawah 7 GB
- Model dilepas sendiri dari RAM setelah 3 menit tidak dipakai
- Qwen3 1.7B (1,0 GB) sebagai pilihan unduhan kecil; Qwen2.5 1.5B tidak lagi ditawarkan
- Daftar model diringkas menjadi satu baris per model; rincian muncul saat diketuk
- Catatan panjang: Ringkas, Daftar tugas, dan Perbaiki ejaan memproses seluruh isi bagian demi bagian
- Tanya catatan mengambil bagian catatan yang paling relevan, bukan hanya bagian awalnya
- Rekap 7 hari terakhir, daftar semua tugas yang belum dicentang, dan "Sarankan pengingat"
- Rapikan otomatis (opsional): judul dan tag yang kosong diisi di latar setelah catatan ditutup

Pondasi
- Kunci aplikasi dengan sidik jari atau PIN layar HP; isi tersembunyi dari daftar aplikasi terbaru
- Cadangan otomatis ke folder pilihan, 7 berkas terakhir; cadangan kini memuat Sampah dan pengingat
- Riwayat versi: 20 versi terakhir tiap catatan, bisa dipulihkan
- Build rilis (bukan debug) dengan kunci tanda tangan tetap
- Perbaikan: anjuran RAM model didasarkan pada pengukuran; sebelumnya Qwen2.5 keliru disebut lebih ringan

## 1.3
- Antarmuka baru: tema netral dengan satu aksen untuk AI, ikon garis, kartu tanpa bingkai
- Satu kolom untuk mencari dan bertanya
- Alat editor menempel di atas papan ketik; seluruh alur AI dalam satu lembar bawah
- Tekan lama kartu untuk sematkan, arsipkan, hapus; bagian "Disematkan"
- Perbaikan: "lari" tidak lagi cocok dengan "Januari"
- Uji tangkapan layar otomatis

## 1.2
- Unduh model dari dalam aplikasi (Gemma 4 E2B, Qwen2.5 1.5B, Gemma 4 E4B) dengan verifikasi SHA-256
- Beberapa model terpasang sekaligus; anjuran berdasarkan RAM
- Prompt diuji dan diperbaiki dengan model sungguhan: tanya-jawab, daftar tugas, keluaran tanpa Markdown
- Batas masukan 6.000 karakter untuk Gemma 4
- Menambah izin internet (hanya untuk unduhan model)

## 1.1
- Sampah, arsip, folder, urutkan, daftar centang, bagikan, terima teks dari aplikasi lain
- Bantu tulis AI, merapikan otomatis, tanya catatan
- Migrasi basis data v2

## 1.0
- Catatan dengan tag, sematan, simpan otomatis, ekspor/impor JSON
- Pencarian TF-IDF + trigram, catatan terkait
- Tag otomatis dan perluasan pencarian dengan LLM lokal
