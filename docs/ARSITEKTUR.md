# Arsitektur

Satu modul (`app`), satu Activity, UI Jetpack Compose, tanpa pustaka navigasi atau injeksi dependensi.
Tujuannya kode yang kecil dan mudah dibaca: ±4.400 baris Kotlin di sebelas berkas.

## Berkas

Semua di `app/src/main/java/com/catatanku/app/`.

| Berkas | Tanggung jawab | Bergantung pada Android? |
|---|---|---|
| `SearchEngine.kt` | Tokenisasi, indeks TF-IDF, pencarian, catatan terkait, kata kunci | Tidak |
| `AiText.kt` | Prompt, pengurai keluaran model, alat teks (centang, hitung kata) | Tidak |
| `TimeParse.kt` | Pengenal waktu bahasa Indonesia, aturan pengulangan pengingat | Tidak |
| `Agent.kt` | Asisten perintah: pengurai perintah, prompt perumus ulang, penerapan pada isi catatan | Tidak |
| `Models.kt` | Katalog model: URL terkunci revisi, ukuran, SHA-256, anjuran RAM | Tidak |
| `Data.kt` | `Note`, SQLite (`NoteDb`), riwayat versi, cadangan JSON | Ya |
| `Reminders.kt` | Alarm sistem, notifikasi, penerima siaran (`ReminderReceiver`) | Ya |
| `LocalAi.kt` | Unduh/impor/ganti model, engine LiteRT-LM, pembangkitan streaming | Ya |
| `AppViewModel.kt` | Seluruh state dan logika aplikasi | Ya |
| `Screens.kt` | Layar daftar, editor, tanya, pengaturan, kunci; lembar pengingat dan riwayat | Ya |
| `Theme.kt` | Warna, tipografi, ikon | Ya |

Lima berkas pertama sengaja murni Kotlin agar bisa diuji di JVM dan dipakai ulang di luar aplikasi.

## Alur data

```
Screens ──aksi──▶ AppViewModel ──▶ NoteDb (SQLite)
   ▲                  │  ├──▶ SearchIndex (di memori, dibangun ulang tiap data berubah)
   └──── state ───────┘  └──▶ LocalAi ──▶ LiteRT-LM (.litertlm)
```

State disimpan sebagai `mutableStateOf` di ViewModel; layar hanya membaca dan memanggil fungsi.
Navigasi adalah satu enum `Screen`.

## Pencarian

Setiap catatan menjadi vektor TF-IDF dari dua jenis fitur:

- **kata** (tanpa kata umum Indonesia/Inggris)
- **trigram karakter** dari kata ≥ 4 huruf, berbobot 0,3

Judul berbobot 2,5× dan tag 2×. Skor adalah kemiripan kosinus, ditambah bonus bila kueri muncul utuh.
Trigram menggantikan stemmer: "berlari" dan "lari" berbagi `lar`, `ari`.

Agar trigram tidak menimbulkan kecocokan palsu, hasil harus lolos **gerbang relevansi**: minimal satu kata kueri
ada persis, atau minimal dua dan separuh trigramnya ada. Tanpa ini "lari" cocok dengan "Januari".

Indeks dibangun ulang penuh tiap perubahan. Itu sederhana dan cukup untuk ribuan catatan; untuk puluhan ribu,
ganti dengan pembaruan inkremental.

## AI lokal

**Runtime**: LiteRT-LM (`litertlm-android`), pengganti MediaPipe LLM Inference yang kini hanya dipelihara.

**Satu permintaan = satu percakapan baru.** Tidak ada riwayat, sehingga konteks kecil dan hasil konsisten
(suhu 0,3, seed tetap). Semua permintaan diserialkan dengan satu `Mutex`.

**Pembagian kerja** — LLM hanya dipakai untuk hal yang memang butuh bahasa:

| Fitur | Mesin |
|---|---|
| Pencarian, catatan terkait | TF-IDF (instan, tanpa model) |
| Perluas pencarian | LLM menghasilkan kata kunci → TF-IDF mencari |
| Tanya catatan | TF-IDF mengambil 4 catatan → LLM menjawab dari situ |
| Tag, judul, bantu tulis | LLM |

**Keluaran model tidak dipercaya begitu saja.** `AiText` membersihkan Markdown dan blok penalaran, mengurai
format secara longgar, dan menolak folder yang tidak ada di daftar pengguna. Bila model hanya mengisi sebagian,
tag diminta ulang secara terpisah.

**Batas masukan** bergantung model (`ModelSpec.inputChars`): 6.000 karakter untuk Gemma 4, 2.800 untuk model kecil,
dan paling banyak 2.400 dalam Mode ringan.

**Catatan panjang.** Aksi bertanda `wholeNote` (Ringkas, Daftar tugas, Perbaiki ejaan) membagi isi di batas paragraf
(`AiText.chunks`, maksimal 8 bagian) dan memproses tiap bagian. Ringkasan digabung lewat satu permintaan tambahan;
daftar tugas dan perbaikan ejaan cukup disambung. Tanya catatan memakai `AiText.bestWindow`: potongan catatan yang
paling banyak memuat kata pertanyaan, bukan bagian awalnya.

**Memori.** Model dilepas dari RAM setelah 3 menit tanpa permintaan. Mode ringan mengecilkan jendela konteks menjadi
2.048 token (`EngineConfig.maxNumTokens`) dan melepas model setelah 20 detik; aktif bawaan bila RAM HP di bawah 7 GB.

### Hasil pengukuran model (15 Nov 2026)

Diukur di desktop Linux, CPU, lewat `litertlm-jvm` 0.19.1 dengan prompt aplikasi ini. Puncak RSS mencakup JVM.
Angka di HP belum diukur.

| Model | Berkas | Puncak RSS | Waktu ringkas | Mutu pada prompt aplikasi |
|---|---|---|---|---|
| Gemma 4 E2B | 2,6 GB | 1,8–1,9 GB (1,65 GB Mode ringan) | 6–8 dtk | Semua aksi baik |
| Qwen2.5 1.5B q8 | 1,6 GB | 2,4 GB | 12 dtk | Merapikan gagal, rekap berantakan |
| Qwen3 1.7B INT4 | 1,0 GB | 2,8 GB (2,0 GB Mode ringan) | 12–21 dtk | Ringkas/tugas/tanya baik; tag dan ejaan lemah |
| Qwen3 0.6B | 0,3–0,6 GB | — | — | Ditolak: ringkasan berulang tanpa isi |
| MiniCPM5 1B | 0,8 GB | — | — | Ditolak: bahasa Indonesia rusak |
| Qwen2 0.5B | 0,6 GB | — | — | Ditolak: templat obrolan gagal di LiteRT-LM 0.19.1 |

Kesimpulan: ukuran berkas tidak menentukan pemakaian RAM. Gemma 4 E2B paling hemat karena bobot 2/4-bit dan tabel
embedding yang dipetakan dari berkas, sehingga ia disarankan untuk semua HP.

### Menambah model

Tambahkan satu `ModelSpec` di `Models.kt`. Ambil revisi, ukuran, dan SHA-256 dari
`https://huggingface.co/api/models/<repo>?blobs=true`, dan pastikan `gated` bernilai `false`.

Model yang "berpikir" dulu (mis. Qwen3) tidak cocok: di pengujian, satu permintaan tag butuh 74 detik.

### Unduhan

`DownloadManager` Android mengunduh ke penyimpanan eksternal milik aplikasi sebagai `.part`. Setelah selesai,
ukuran dan SHA-256 diperiksa, baru berkas diganti nama dan dijadikan model aktif. Status dipantau dengan polling,
dan dilanjutkan bila aplikasi sempat ditutup.

## Asisten perintah

Kolom cari menerima perintah. Alurnya selalu **perintah → rencana → persetujuan → jalankan → bisa diurungkan**.

```
perintah ──▶ Agent.parse (aturan) ──berhasil──▶ langkah (≤ 3)
                 │ gagal, ada model
                 └──▶ LLM merumuskan ulang ke bentuk baku ──▶ Agent.parseModel (aturan yang sama)
langkah ──▶ AppViewModel.resolve: cari catatan sasaran (TF-IDF), periksa bisa/tidaknya ──▶ lembar rencana
setuju  ──▶ runAgent: tulis lewat persist(), simpan keadaan lama untuk undoAgent
```

- **Aturan dulu.** `Agent.parse` mengenali pola umum dengan ekspresi reguler: instan, deterministik, dan jalan tanpa model.
  Sepuluh jenis langkah (`AgentStep`): buat catatan, setel/hapus pengingat, tambah teks/tugas, centang butir, beri tag,
  pindah folder, semat, arsip, dan pindah ke Sampah. Beberapa perintah disambung dengan "lalu", "dan", atau koma, tetapi
  hanya bila diikuti kata kerja perintah, sehingga "catat beli susu dan roti" tetap satu catatan.
- **LLM hanya merumuskan ulang.** Bila aturan gagal, model diminta menulis ulang kalimat ke bentuk baku
  (`Agent.plannerPrompt`), lalu hasilnya diurai aturan yang sama. Model tidak memanggil fungsi, tidak menghitung tanggal,
  dan tidak menyentuh data. Ini pola yang sama dengan "Sarankan pengingat".
- **Pengaman keluaran model.** Tempat isian yang tersalin apa adanya dibuang; pertanyaan tidak pernah dianggap perintah;
  "catat …" hanya diterima bila kalimat aslinya memang menyebut mencatat atau menyimpan; "catat X" yang diikuti
  "ingatkan X" digabung menjadi satu catatan berpengingat.
- **Sasaran tidak ditebak diam-diam.** Catatan sasaran dicari dengan `SearchIndex`; tiga teratas ditawarkan sebagai pilihan.
  Langkah yang tidak bisa dijalankan (waktu tak dikenali, catatan tak ditemukan, butir tak ada) dijelaskan dan menahan
  tombol Jalankan.
- **Bisa dibatalkan.** `undoAgent` menghapus catatan yang dibuat dan memulihkan keadaan lama catatan yang diubah. Isi yang
  diubah juga masuk riwayat versi. "Hapus" selalu berarti Sampah. Enter di kolom cari tidak membuka rencana untuk
  perintah hapus atau arsip; tombol Jalankan harus ditekan.

### Hasil uji perumus ulang (15 Nov 2026)

Gemma 4 E2B di desktop Linux lewat `litertlm-jvm` 0.19.1, 2–3,6 detik per perintah. Dari 19 kalimat bebas yang tidak
dikenali aturan: 15 menghasilkan rencana yang benar atau ditolak dengan benar, 3 menghasilkan rencana keliru (terlihat
di lembar rencana sebelum dijalankan), 1 tidak dikenali. Pengingat, catatan baru, arsip, dan hapus andal; memberi tag
dan menyemat dengan kalimat bebas paling sering keliru. Kecepatan di HP dan model lain belum diukur.

## Pengingat

`AlarmManager` membangunkan `ReminderReceiver`, yang menampilkan notifikasi. Tidak ada layanan latar yang terus hidup.

- **Tepat waktu**: `setAlarmClock` (gaya alarm) atau `setExactAndAllowWhileIdle`. Bila izin alarm tepat waktu dicabut,
  jatuh ke `setAndAllowWhileIdle`, yang bisa terlambat beberapa menit.
- **Gaya alarm**: kanal notifikasi tersendiri dengan nada dan volume alarm, `FLAG_INSISTENT` (nada diulang). Setelah
  2 menit tanpa tanggapan, dering dihentikan dan diganti notifikasi "Terlewat".
- **Setelah berbunyi**: pengingat berulang maju ke jadwal berikutnya; yang sekali ditandai `remind_fired` dan tetap
  menempel pada catatan (merah di daftar) sampai "Selesai" ditekan atau catatannya dibuka dari notifikasi.
- **Bertahan**: alarm disetel ulang saat HP dinyalakan, aplikasi diperbarui, jam diubah, dan tiap aplikasi dibuka.
  Pengingat yang terlewat selagi HP mati berbunyi segera setelahnya.
- **Satu pintu**: semua penulisan catatan di `AppViewModel` lewat `persist()`, yang memanggil `Reminders.sync()`.
  Penerima alarm hanya mengubah kolom pengingat (`NoteDb.setReminder`) agar tidak menimpa suntingan yang berjalan,
  lalu memberi tahu layar lewat `Reminders.changed`.

**Pengenal waktu** (`TimeParse`) deterministik: ekspresi reguler untuk tanggal, hari, jam, dan kata relatif, lalu
dipasangkan per baris. LLM tidak menghitung tanggal. "Sarankan pengingat" hanya meminta LLM menyalin frasa waktunya
ke bentuk baku, dan `TimeParse` yang mengubahnya menjadi tanggal. Hasilnya selalu berupa saran yang dikonfirmasi
pengguna di lembar pengingat.

## Keamanan dan cadangan

- **Kunci aplikasi** memakai `BiometricPrompt` sistem dengan PIN layar sebagai cadangan (Android 8–9: layar konfirmasi
  kredensial). Aplikasi tidak menyimpan sandi. Jeda di bawah 60 detik (pemilih berkas, dialog izin) tidak mengunci
  ulang. `FLAG_SECURE` menyembunyikan isi dari daftar aplikasi terbaru. Ini gerbang akses, bukan enkripsi: basis data
  mengandalkan enkripsi penyimpanan bawaan Android.
- **Cadangan otomatis** ditulis ke folder pilihan pengguna lewat Storage Access Framework saat aplikasi ditinggalkan
  dan ada perubahan. Satu berkas per hari, 7 terakhir disimpan. Berkasnya JSON polos, tidak dienkripsi.
- **Riwayat versi**: sekali per sesi sunting, keadaan catatan saat dibuka disimpan ke tabel `versions` sebelum isinya
  berubah. 20 versi terakhir per catatan.

## Penyimpanan

SQLite lewat `SQLiteOpenHelper`, satu tabel `notes`. Tag disimpan sebagai teks berpemisah `U+001F`.
Skema v2 menambah `folder`, `archived`, `deleted` (stempel waktu; 0 = tidak di sampah).
Skema v3 menambah kolom pengingat (`remind_at`, `remind_repeat`, `remind_alarm`, `remind_fired`) dan tabel `versions`.
`NoteDb` adalah satu objek untuk seluruh proses, karena layar dan penerima alarm memakai basis data yang sama.

Simpan otomatis ditulis **sinkron** di thread utama. Satu baris hanya beberapa milidetik, dan ini mencegah
catatan ganda bila simpan terpicu dua kali berdekatan.

## Desain

Permukaan netral, tinta sebagai warna utama, dan satu aksen amber yang hanya dipakai untuk AI.
Warna tetap (tidak mengikuti wallpaper) agar konsisten. Ikon digambar sendiri di `Theme.kt` supaya tidak perlu
pustaka ikon besar.

## Pengujian

| Kelas | Isi |
|---|---|
| `SearchEngineTest` | Morfologi, salah ketik, kecocokan palsu, catatan terkait |
| `AiTextTest` | Pengurai keluaran model, pembersih Markdown, tombol centang, batas prompt, katalog, pemotongan catatan panjang |
| `TimeParseTest` | 40-an frasa waktu, termasuk yang BUKAN waktu ("pace 6:30", "km 24.500"), dan pengulangan |
| `ReminderTest` | Alarm, notifikasi, tunda, selesai, gaya alarm, HP dinyalakan ulang, riwayat versi, cadangan, migrasi v2 → v3 |
| `ShotsLight` / `ShotsDark` / `ShotsTall` | Render 17 layar ke PNG |

Uji tangkapan layar menjalankan aplikasi sungguhan di Robolectric dengan basis data nyata dan berkas model tiruan
(model tidak pernah dimuat).
