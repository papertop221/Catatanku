# Arsitektur

Satu modul (`app`), dua Activity (`MainActivity` dan lembar `ProcessTextActivity`), UI Jetpack Compose, tanpa pustaka navigasi atau injeksi dependensi.
Tujuannya kode yang kecil dan mudah dibaca: ±5.800 baris Kotlin di tujuh belas berkas.

## Berkas

Semua di `app/src/main/java/com/catatanku/app/`.

| Berkas | Tanggung jawab | Bergantung pada Android? |
|---|---|---|
| `SearchEngine.kt` | Tokenisasi, indeks TF-IDF, pencarian, catatan terkait, kata kunci | Tidak |
| `AiText.kt` | Prompt teks & multimodal, pengurai keluaran model, alat teks (ketuk centang, lanjut daftar, hitung kata) | Tidak |
| `TimeParse.kt` | Pengenal waktu bahasa Indonesia, aturan pengulangan pengingat | Tidak |
| `Agent.kt` | Asisten perintah: pengurai perintah (termasuk satu kalimat jadi pengingat + tugas + tag + folder), prompt perumus ulang | Tidak |
| `Models.kt` | Katalog model: URL terkunci revisi, ukuran, SHA-256, anjuran RAM | Tidak |
| `Data.kt` | `Note`, SQLite (`NoteDb` skema v4), riwayat versi, cadangan JSON | Ya |
| `Media.kt` | Lampiran gambar (kompresi JPEG ≤ 1600px), perekam suara WAV 16 kHz mono (`VoiceRecorder`), pemutar suara (`AudioPlayer`) | Ya |
| `Reminders.kt` | Alarm sistem, notifikasi, penerima siaran (`ReminderReceiver`), deteksi terlambat, uji pengingat | Ya |
| `LocalAi.kt` | Unduh/impor/ganti model, engine LiteRT-LM (teks, gambar, suara; satu per proses), pembangkitan streaming | Ya |
| `SystemIntegration.kt` | Menu teks terpilih (`ProcessTextActivity`), tile Setelan Cepat (`QuickNoteTile`), pintasan ikon, kalender, setelan baterai pabrikan (`Oem`) | Ya |
| `AppViewModel.kt` | Seluruh state dan logika aplikasi | Ya |
| `ListScreen.kt` | Layar utama: blok "Hari ini", jawaban & daftar tugas interaktif, daftar, bilah bawah, geser baris | Ya |
| `EditorScreen.kt` | Editor, Undo/Redo ketikan, lampiran gambar/suara, dan simpan otomatis | Ya |
| `SettingsScreen.kt` | Pengaturan | Ya |
| `Sheets.kt` | Lembar bawah dan dialog: pengingat, bantu tulis, rencana asisten, riwayat versi, folder, kunci | Ya |
| `UiKit.kt` | Komponen bersama (tombol, baris, lembar, menu) | Ya |
| `Theme.kt` | Warna, huruf, bentuk, ikon | Ya |

Lima berkas pertama sengaja murni Kotlin agar bisa diuji di JVM dan dipakai ulang di luar aplikasi.

## Alur data

```
Screens ──aksi──▶ AppViewModel ──▶ NoteDb (SQLite)
   ▲                  │  ├──▶ SearchIndex (di memori, diperbarui di thread latar)
   └──── state ───────┘  └──▶ LocalAi ──▶ LiteRT-LM (.litertlm)
```

State disimpan sebagai `mutableStateOf` di ViewModel; layar hanya membaca dan memanggil fungsi.
Navigasi adalah satu enum `Screen` (daftar, editor, pengaturan).

Yang mahal dihitung sekali saat data berubah, bukan tiap layar digambar: `Stats` (tag, folder, jumlah), `shown`
(daftar yang tampil), dan `today`. Simpan dari editor menaruh hasilnya langsung ke daftar di memori (`putLocal`);
basis data hanya dibaca ulang seluruhnya bila berubah di luar layar (alarm, impor, proses latar).

## Pencarian

Setiap catatan menjadi vektor TF-IDF dari dua jenis fitur:

- **kata** (tanpa kata umum Indonesia/Inggris)
- **trigram karakter** dari kata ≥ 4 huruf, berbobot 0,3

Judul berbobot 2,5× dan tag 2×. Skor adalah kemiripan kosinus, ditambah bonus bila kueri muncul utuh.
Trigram menggantikan stemmer: "berlari" dan "lari" berbagi `lar`, `ari`.

Agar trigram tidak menimbulkan kecocokan palsu, hasil harus lolos **gerbang relevansi**: minimal satu kata kueri
ada persis, atau minimal dua dan separuh trigramnya ada. Tanpa ini "lari" cocok dengan "Januari".

Indeks berbentuk ringkas: tiap fitur diberi nomor, tiap catatan menyimpan larik nomor fitur dan bobotnya, dan ada
daftar terbalik (fitur → catatan), sehingga pencarian hanya menyentuh catatan yang memuat kata kuncinya.
`SearchIndex.update` memakai ulang hasil catatan yang tidak berubah, jadi satu simpan hanya menghitung ulang satu
catatan. Pembaruan dan pencarian berjalan di thread latar; hanya hasil ketikan terakhir yang dipakai.

Pengukuran dengan catatan sintetis (`PerfTest`, JVM desktop; HP kelas menengah kira-kira 3–6× lebih lambat):

| Catatan | Bangun dari awal | Satu catatan berubah | Cari | Terkait |
|---|---|---|---|---|
| 300 | 51 ms | 3 ms | 0,7 ms | 0,6 ms |
| 1.000 | 118 ms | 7 ms | 1,3 ms | 0,7 ms |
| 3.000 | 334 ms | 21 ms | 1,6 ms | 1,6 ms |

Sebelum 1.6, tiap simpan otomatis membangun indeks dari awal di thread utama (kolom kedua).

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

- **Tepat waktu**: `setAlarmClock` (gaya alarm, atau semua pengingat bila "Jadwalkan sebagai alarm sistem" aktif) atau
  `setExactAndAllowWhileIdle`. Bila izin alarm tepat waktu dicabut, jatuh ke `setAndAllowWhileIdle`, yang bisa terlambat
  beberapa menit.
- **Tahan penghemat baterai**: di HP dengan pembatas latar agresif (`Oem.aggressive`: Oppo, Vivo, Realme, OnePlus, Xiaomi,
  Huawei, Transsion), mode `robust` bawaan menyala sehingga semua pengingat memakai `setAlarmClock` — jenis alarm yang
  dikecualikan dari Doze dan paling jarang ditahan sistem. Bila pengingat tetap berbunyi terlambat ≥ 3 menit saat HP
  menyala (`SystemClock.elapsedRealtime() >= late + 60_000L`, sehingga pengingat yang terlewat karena HP mati tidak
  ikut dihitung), `Reminders.noteLateness` mencatatnya untuk ditampilkan di Pengaturan bersama langkah setelan per
  pabrikan (`Oem.steps`), pembuka halaman mulai otomatis (`Oem.openAutostart`), permintaan pengecualian baterai, dan
  tombol "Uji pengingat" 2 menit (`Reminders.startTest`). Lembar pengingat juga menyediakan "Salin juga ke Kalender HP"
  (`addToCalendar`) lewat intent `ACTION_INSERT` tanpa meminta izin kalender.
- **Gaya alarm**: kanal notifikasi tersendiri dengan nada dan volume alarm, `FLAG_INSISTENT` (nada diulang). Setelah
  2 menit tanpa tanggapan, dering dihentikan dan diganti notifikasi "Terlewat".
- **Setelah berbunyi**: pengingat berulang maju ke jadwal berikutnya; yang sekali ditandai `remind_fired` dan tetap
  menempel pada catatan (merah di daftar) sampai "Selesai" ditekan atau catatannya dibuka dari notifikasi.
- **Bertahan**: alarm disetel ulang saat HP dinyalakan, aplikasi diperbarui, jam diubah, dan tiap aplikasi dibuka.
  Pengingat yang terlewat selagi HP mati berbunyi segera setelahnya.
- **Satu pintu**: semua penulisan catatan di `AppViewModel` lewat `persist()`, dan catatan dari luar layar utama lewat
  `saveFromOutside()`, yang keduanya memanggil `Reminders.sync()` serta menandai cadangan otomatis (`dirty`).
  Penerima alarm hanya mengubah kolom pengingat (`NoteDb.setReminder`) agar tidak menimpa suntingan yang berjalan,
  lalu memberi tahu layar lewat `Reminders.changed`.

## Integrasi sistem

`SystemIntegration.kt` menampung pintu masuk dari luar aplikasi:

- **Menu teks terpilih (`ProcessTextActivity`)**: menerima `ACTION_PROCESS_TEXT` dari aplikasi mana pun. Teks bisa
  langsung disimpan sebagai catatan, disimpan beserta pengingat bila `TimeParse.best(text)` menemukan waktu, atau
  diolah dengan AI lokal (Ringkas, Perbaiki ejaan, Ambil daftar tugas). Bila aplikasi asal mengizinkan penyuntingan
  (`EXTRA_PROCESS_TEXT_READONLY == false`), tombol utamanya mengganti teks terpilih di aplikasi asal lewat `RESULT_OK`.
  Layar ini hanya menulis catatan baru dan tidak menampilkan catatan yang ada, sehingga aman dipakai walau kunci
  aplikasi aktif. Begitu ditutup (`onDestroy`), ia memberi sinyal `TRIM_MEMORY_UI_HIDDEN` ke `LocalAi.get(this)` agar
  model tidak menahan RAM.
- **Tile Setelan Cepat (`QuickNoteTile`)**, **pintasan ikon dinamis (`publishShortcuts`)**, dan **intent publik**
  (`com.catatanku.app.NEW_NOTE`, `SHOW_REMINDERS`, `SHOW_TASKS`) membuka `MainActivity` langsung ke catatan baru,
  tampilan Pengingat, atau daftar tugas yang belum selesai.

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

SQLite lewat `SQLiteOpenHelper`, satu tabel `notes`. Tag dan nama berkas lampiran disimpan sebagai teks berpemisah `U+001F`.
Skema v2 menambah `folder`, `archived`, `deleted` (stempel waktu; 0 = tidak di sampah).
Skema v3 menambah kolom pengingat (`remind_at`, `remind_repeat`, `remind_alarm`, `remind_fired`) dan tabel `versions`.
Skema v4 menambah kolom `attachments` untuk lampiran gambar (`img_*.jpg`) dan catatan suara (`aud_*.wav`) yang disimpan
di `filesDir/attachments/`. Berkas lampiran yatim dibersihkan otomatis saat catatan dihapus permanen atau Sampah dikosongkan.
`NoteDb` adalah satu objek untuk seluruh proses, karena layar dan penerima alarm memakai basis data yang sama.

Simpan otomatis berjalan tiap jeda mengetik (0,7 detik) dan ditulis **sinkron**, agar tidak ada catatan ganda bila
simpan terpicu dua kali berdekatan. Supaya murah, basis data memakai WAL dengan `synchronous=NORMAL`: satu simpan
tidak memaksa tulis ke penyimpanan. Basis data tetap konsisten bila HP mati mendadak; yang bisa hilang hanya
suntingan beberapa detik terakhir. Saat aplikasi ditinggalkan, `NoteDb.flush()` memaksanya tertulis.
Alarm sistem hanya disentuh bila pengingat atau status sampah catatannya berubah.

## Desain

Aturannya ada di [DESAIN.md](DESAIN.md): permukaan netral, tinta sebagai warna utama, satu aksen amber yang berarti
waktu, baris datar tanpa kartu dan bayangan, dan satu set ikon garis buatan sendiri (tanpa pustaka ikon).

## Ukuran dan memori

- **APK**: build rilis memakai R8 dan penyusutan sumber daya. Kode Java/Kotlin turun dari 24,8 MB menjadi 4,4 MB;
  APK dari 32,0 MB menjadi 25,2 MB. Sisanya hampir seluruhnya mesin AI native (`liblitertlm_jni.so`, 22,7 MB).
  Seluruh paket LiteRT-LM dikecualikan dari penyusutan karena dipanggil lewat JNI (`app/proguard-rules.pro`).
- **Model AI** tidak dimuat saat aplikasi dibuka. Sebelum dimuat, RAM yang tersedia diperiksa; bila kurang, permintaan
  ditolak dengan pesan yang jelas (bisa dipaksa lewat "Uji muat"). Model dilepas setelah menganggur, lebih cepat
  begitu aplikasi ditinggalkan, dan segera bila sistem memberi sinyal kekurangan memori (`onTrimMemory`).
- **Jendela awal** memakai warna layar pertama, jadi tidak ada kilatan putih saat aplikasi dibuka.

## Pengujian

| Kelas | Isi |
|---|---|
| `SearchEngineTest` | Morfologi, salah ketik, kecocokan palsu, catatan terkait |
| `AiTextTest` | Pengurai keluaran model, pembersih Markdown, tombol centang, batas prompt, katalog, pemotongan catatan panjang |
| `TimeParseTest` | 40-an frasa waktu, termasuk yang BUKAN waktu ("pace 6:30", "km 24.500"), dan pengulangan |
| `ReminderTest` | Alarm, mode tahan penghemat baterai (`setAlarmClock`), deteksi terlambat vs HP mati, uji mandiri, notifikasi, tunda, selesai, gaya alarm, HP dinyalakan ulang, catatan dari luar (`saveFromOutside`), riwayat versi, cadangan, migrasi v2 → v3 |
| `AgentTest` | Pengurai perintah asisten, penerapan langkah, dan pengaman keluaran model |
| `PerfTest` | Pengukuran waktu bangun indeks, pembaruan satu catatan, pencarian, dan catatan terkait |
| `ShotsLight` / `ShotsDark` / `ShotsTall` | Render 23 layar ke PNG (termasuk lembar teks terpilih) |

Uji tangkapan layar menjalankan aplikasi sungguhan di Robolectric dengan basis data nyata dan berkas model tiruan
(model tidak pernah dimuat).
