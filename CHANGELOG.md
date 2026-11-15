# Riwayat perubahan

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
