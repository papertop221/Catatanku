# Riwayat perubahan

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

Lain-lain
- `LocalAi` menjadi satu objek per proses agar layar utama dan lembar teks terpilih tidak memuat model dua kali
- Pemformat waktu (`whenLabel`) memakai `DateTimeFormatter` bersama tanpa membuat `SimpleDateFormat` tiap baris;
  filter bahasa sumber daya (`id`, `en`)

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
