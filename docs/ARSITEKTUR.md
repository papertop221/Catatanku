# Arsitektur

Satu modul Gradle (`app`), dua Activity (`MainActivity` dan lembar `ProcessTextActivity`), UI Jetpack Compose, tanpa pustaka navigasi atau injeksi dependensi.
Pada `prd-1.8`, kode diorganisasi ke dalam subpaket domain/data/AI/UI di bawah `com.catatanku.app` (31 berkas Kotlin), sementara komponen Android Manifest tetap berada pada paket akar `com.catatanku.app` demi kompatibilitas pembaruan.

## Berkas

Semua di `app/src/main/java/com/catatanku/app/`.

| Paket / Berkas | Tanggung jawab | Murni JVM? |
|---|---|---|
| `MainActivity.kt`, `ReminderReceiver.kt`, `SystemIntegration.kt` | Pintu masuk Manifest (`MainActivity`, `ProcessTextActivity`, `QuickNoteTile`, `ReminderReceiver`) | Tidak |
| `domain/model/` (`NoteModels.kt`, `BackupModels.kt`) | Model domain (`Note`, `Version`, `Repeat`, `AiAction`, `AgentStep`, model manifest/progres cadangan) | Ya |
| `domain/usecase/` (`SearchEngine.kt`, `EmbeddingCodec.kt`, `TimeParse.kt`) | Indeks TF-IDF + trigram, waktu deterministik, sisa codec embedding (hanya untuk migrasi lama) | Ya |
| `data/backup/` (`BackupArchive.kt`, `BackupCodec.kt`, `JsonStream.kt`) | Ekspor/impor ZIP lengkap (`catatanku-backup.zip`), SHA-256 `checksums.json`, pelindung traversal/ZIP bomb, JSON streaming O(1) | Ya |
| `data/database/NoteDb.kt` | SQLite terenkripsi AES-256-GCM (`catatan.db`, skema v6: tabel `notes`, `versions`, `embeddings`), migrasi v1→v6, kolom `vault`, impor transaksional | Tidak |
| `data/repository/NoteRepository.kt` | Repositori tunggal, pemulihan atomik berbasis *staging* + rollback, dan `AutoBackup` harian terenkripsi | Tidak |
| `ai/modelcatalog/Models.kt` | Katalog model dengan URL terkunci revisi, SHA-256, kapabilitas (`supportsVision`, `supportsAudio`, `isEmbedding`), perkiraan RAM | Ya |
| `ai/performance/` (`AiPerformance.kt`, `AndroidPerfMonitor.kt`) | Profil runtime per SoC/SDK/runtime/model, kebijakan MTP & anggaran token per tugas, `MemoryPolicy`, metrik thermal/RSS (`VmHWM`) | Sebagian (`AiPerformance.kt` ya) |
| `ai/inference/` (`AiText.kt`, `Agent.kt`) | Prompt teks/multimodal, sanitisasi input & karakter Bidi/token model, pembersih keluaran, perencana & pengurai perintah asisten | Ya |
| `ai/engine/` (`LocalAi.kt`, `LocalEmbeddingProvider.kt`) | Unduh/impor model, cek Mode Offline Mutlak & integritas model, `Engine` LiteRT-LM (`Content.ImageBytes`/`AudioBytes`), `EmbeddingEngine` opsional | Tidak |
| `security/` (`VaultCrypto.kt`, `AppPinCrypto.kt`, `BackupCrypto.kt`, `InputSecurity.kt`, `DeviceVaultKey.kt`, `SecurityPrefs.kt`) | Enkripsi data-at-rest AES-256-GCM, PBKDF2 600k, enkripsi cadangan streaming `CTKE` & hibrida EC P-256 `CTKH`, PIN aplikasi, validasi URI/teks/gambar, kunci Android Keystore, *panic wipe* | Sebagian (`VaultCrypto`, `AppPinCrypto`, `BackupCrypto`, `InputSecurity` ya) |
| `reminders/Reminders.kt` | Alarm tahan hemat baterai (`AlarmManager`), pelindung duplikat/jam berubah (`EXTRA_DUE`), penyamaran notifikasi kunci/Brankas, `Oem`, kalender sistem | Tidak |
| `media/Media.kt` | Lampiran gambar & audio terenkripsi `CTKA\x01`, tolak bom piksel, transkode paksa WAV 16 kHz tanpa metadata, pemutaran `MediaDataSource` & OCR di RAM tanpa berkas temp polos | Tidak |
| `ui/` (`AppViewModel.kt`, `ListScreen.kt`, `EditorScreen.kt`, `SettingsScreen.kt`, `Sheets.kt`, `UiKit.kt`, `Theme.kt`) | State aplikasi, layar utama/editor/pengaturan, dialog progres/sandi cadangan, komponen & tema "Kertas & Tinta" | Tidak |

Seluruh logika domain, pencarian, pengurai waktu, asisten, prompt AI, pembungkus JSON/ZIP, enkripsi cadangan, kebijakan memori, dan profil performa sengaja murni Kotlin agar cepat diuji di JVM tanpa perangkat.

## Alur data

```
Screens ──aksi──▶ AppViewModel ──▶ NoteDb (SQLite)
   ▲                  │  ├──▶ SearchIndex (di memori, diperbarui di thread latar)
   └──── state ───────┘  └──▶ LocalAi ──▶ LiteRT-LM (.litertlm)
```

State disimpan sebagai `mutableStateOf` di ViewModel; layar hanya membaca dan memanggil fungsi.
Navigasi adalah satu enum `Screen` (daftar, editor, pengaturan).

Yang mahal dihitung sekali saat data berubah, bukan tiap layar digambar: `Stats` (tag, folder, jumlah), `shown`
(daftar yang tampil), dan `today`. Selama pengguna menyunting di Editor (`deferList`), simpan otomatis hanya memperbarui
catatan dan peta `notesById` di memori, lalu menunda pembaruan `Stats`/`SearchIndex` hingga keluar dari Editor atau
berpindah catatan agar mengetik tetap ringan di HP spek rendah. Basis data hanya dibaca ulang seluruhnya bila berubah
di luar layar (alarm, impor, proses latar), dan pemindahan/pembubaran folder dijalankan dalam satu transaksi di
`Dispatchers.IO` (`NoteDb.moveFolder`).

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

**Multimodal dua langkah (gambar & suara).** Untuk "Ambil tugas" dari gambar atau rekaman suara, model tidak diminta
langsung mengarang tugas dari piksel atau audio mentah. Gambar melewati gerbang OCR ketat (`AiText.imageOcrPrompt` +
`isNoTextOrSceneDescription`) agar foto biasa tanpa teks tugas tidak dikarang menjadi ceklis; suara ditranskripsikan
dulu dengan prompt ringkas (`AiText.audioTranscribePrompt` + `cleanTranscript`) agar model kecil tidak memantulkan kata
instruksi. Sesudahnya waktu dihitung secara deterministik oleh `TimeParse` (lalu ditawarkan langsung sebagai pengingat
di `AiSheet`), dan daftar ceklis `☐` dipecah dari teks yang bersih.

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
  Layar ini hanya menulis catatan baru dan tidak menampilkan catatan yang ada, serta menerapkan `FLAG_SECURE` bila kunci
  aplikasi aktif. Begitu ditutup (`onDestroy`), ia memberi sinyal `TRIM_MEMORY_UI_HIDDEN` ke `LocalAi.get(this)` agar
  model tidak menahan RAM.
- **Tile Setelan Cepat (`QuickNoteTile`)**, **pintasan ikon dinamis (`publishShortcuts`)**, dan **intent publik**
  (`com.catatanku.app.NEW_NOTE`, `SHOW_REMINDERS`, `SHOW_TASKS`) membuka `MainActivity` langsung ke catatan baru,
  tampilan Pengingat, atau daftar tugas yang belum selesai. Saat kunci aplikasi aktif, semua intent masuk (kecuali saat
  sudah terautentikasi dan masa tenggang aktif) ditunda hingga pengguna membuka kunci, dan pembukaan dari notifikasi
  pengingat (`EXTRA_OPEN`) diverifikasi dengan token rahasia per pemasangan (`EXTRA_TOKEN`). URI eksternal pada
  `ACTION_SEND` dan impor disaring (`Attachments.isSafeExternalUri`) agar hanya `content://` pihak ketiga yang diterima.

**Pengenal waktu** (`TimeParse`) deterministik: ekspresi reguler untuk tanggal, hari, jam, dan kata relatif, lalu
dipasangkan per baris. LLM tidak menghitung tanggal. "Sarankan pengingat" hanya meminta LLM menyalin frasa waktunya
ke bentuk baku, dan `TimeParse` yang mengubahnya menjadi tanggal. Hasilnya selalu berupa saran yang dikonfirmasi
pengguna di lembar pengingat.

## Keamanan dan cadangan

- **Enkripsi penyimpanan & Brankas**: `DeviceVaultKey` membungkus DEK AES-256 di Android Keystore (`catatanku_vault_kek_v1`) untuk mengenkripsi isi catatan, riwayat versi, vektor embedding, serta lampiran (`CTKA\x01`, AES-256-GCM per blok 64 KB) lewat `VaultCrypto`. Catatan **Brankas** (`vault = true`) terisolasi dari daftar, pencarian, *Hari ini*, AI Q&A, serta ekspor tak terenkripsi, dan hanya dimuat saat Brankas dibuka ulang.
- **Kunci aplikasi & privasi perangkat**: `BiometricPrompt` (`BIOMETRIC_STRONG` + kredensial perangkat) serta PIN mandiri `AppPinCrypto` (PBKDF2 600k + *backoff* & opsi *wipe* 10× gagal), waktu kunci otomatis (Segera / 15 detik / 1 menit), kunci seketika saat layar mati (`ACTION_SCREEN_OFF`), pembersihan RAM (`scrubSensitiveMemory`), penguncian `ProcessTextActivity`, perisai layar (`FLAG_SECURE` + `setRecentsScreenshotEnabled(false)`), keyboard penyamaran (`IME_FLAG_NO_PERSONALIZED_LEARNING`), hapus otomatis papan klip (30 detik + `EXTRA_IS_SENSITIVE`), serta **Mode Offline Mutlak**.
- **Cadangan lengkap ZIP & AutoBackup terenkripsi**: `BackupArchive` mengekspor/mengimpor `catatanku-backup.zip` dengan SHA-256 `checksums.json`, enkripsi kata sandi streaming `CTKE\x01` (PBKDF2 600k, tanpa berkas polos sementara), dan enkripsi hibrida EC P-256 `CTKH\x01` agar `AutoBackup` harian tetap berjalan otomatis terenkripsi tanpa menyimpan sandi pemulihan di perangkat.

## Penyimpanan

SQLite lewat `SQLiteOpenHelper` (`NoteDb`): tabel `notes`, `versions`, dan `embeddings` (skema v6). Kolom teks (`title`, `body`, `tags`, `folder`, `versions.title`, `versions.body`, `embeddings.content_hash`, `embeddings.vec`) disimpan terenkripsi `CTKV1:`/`CTKB\x01`.
Skema v2 menambah `folder`, `archived`, `deleted`.
Skema v3 menambah kolom pengingat (`remind_at`, `remind_repeat`, `remind_alarm`, `remind_fired`) dan tabel `versions`.
Skema v4 menambah kolom `attachments` untuk lampiran gambar (`img_*.jpg`) dan catatan suara (`aud_*.wav`) di `filesDir/attachments/`.
Skema v5 menambah tabel `embeddings` untuk pencarian semantik. Fitur itu dihapus di 1.10: tabel tetap ada agar migrasi lama berjalan, tetapi isinya dan berkas model embedding dimusnahkan sekali saat pertama dibuka (`NoteRepository.purgeSemanticData`) dan tidak ada lagi yang menulis ke sana.
Skema v6 menambah kolom `vault` untuk catatan tersembunyi Brankas dan migrasi enkripsi otomatis di `onOpen`.

## AI lokal & Pencarian Semantik Hibrida

- **Pemilihan backend berbasis data (`AiPerformance.kt`, `BENCHMARK.md`)**: di Moto G86 Power 5G (`mt6878`), `CPU-4` (`Backend.CPU(threadCount = 4)`) + profil konteks **Hemat (`maxNumTokens = 2048`)** dengan `SpeculativeDecodingMode.ADAPTIVE` per tugas memberikan muat ±1 detik, hemat ±575–680 MB RAM dibanding GPU, serta menyamai/mengungguli GPU di tugas teks harian. Vision/OCR memakai GPU + turunan gambar sementara ≤ 1024 px di `cacheDir` tanpa mengubah gambar asli (`vision-gpu-1024`: ±11,5 dtk, -452 MB RSS). Perangkat lain memulai dari bawaan konservatif CPU-4 lalu dapat menyimpan profil terukur via **Penyesuaian kecepatan** (`Ukur kecepatan`) di Pengaturan (`PerformanceProfileStore`).
- **Kebijakan memori adaptif (`MemoryPolicy`)**: menggunakan RAM fisik aktual tanpa swap/RAM Boost, `ActivityManager.MemoryInfo` (`availMem`, `lowMemory`), dan sinyal `onTrimMemory` untuk memilih jendela konteks & idle timeout tanpa pemanggilan `System.gc()` agresif.
- **Pencarian**: murni `SearchIndex` (TF-IDF + trigram, instan, deterministik), opsional diperluas kata kunci dari model bahasa atas permintaan.

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
- **Model AI** tidak dimuat saat aplikasi dibuka. Sebelum dimuat, `MemoryPolicy` memeriksa RAM fisik aktual (tanpa swap/RAM Boost), `availMem`, sinyal `lowMemory`, `onTrimMemory`, dan status foreground/background dengan cadangan +250 MB untuk multimodal atau +200 MB untuk GPU; bila tidak cukup, model ditolak dengan pesan yang jelas atau fallback aman ke CPU. `System.gc()` agresif tidak lagi dipicu. Mode ringan mengunci konteks 2.048 token dan memperpendek idle timeout (20–30 detik vs 3 menit).
- **Media & UI**: lampiran gambar didekode di thread IO dengan `ImageDecoder` / `inSampleSize` + `RGB_565` (720 px untuk pratinjau editor, ≤ 1600 px layar penuh), dan gambar OCR diolah lewat turunan sementara ≤ 1024 px di `cacheDir` tanpa mengubah berkas asli.
- **Jendela awal** memakai warna layar pertama, jadi tidak ada kilatan putih saat aplikasi dibuka.

## Pengujian

| Kelas | Isi |
|---|---|
| `SearchEngineTest` | Morfologi, salah ketik, kecocokan palsu, catatan terkait |
| `AiTextTest` & `AiQualityTest` | Pengurai keluaran model, pembersih Markdown, tombol centang, anggaran prompt 2048/3072/4096, pemotongan catatan panjang, mutu bahasa Indonesia |
| `BackupArchiveTest` | Ekspor/impor `catatanku-backup.zip` (100 catatan + gambar/audio + versi), enkripsi AES-256-GCM + salah sandi/terpotong, verifikasi SHA-256, anti-traversal/ZIP bomb, JSON lama, pembatalan |
| `SecurityHardeningTest` | Enkripsi `VaultCrypto` & lampiran `CTKA\x01`, `AppPinCrypto`, `BackupCrypto` streaming `CTKE`/`CTKH` hibrida, isolasi Brankas saat ekspor polos, sanitisasi Bidi/LLM & bom piksel |
| `PerformanceAndMemoryTest` | Pemilihan profil SoC (`mt6878` vs konservatif vs cache), kebijakan MTP per tugas, `MemoryPolicy`, `BenchmarkStats` median/p95, `InputSecurity` |
| `TimeParseTest` | 40-an frasa waktu, termasuk yang BUKAN waktu ("pace 6:30", "km 24.500"), dan pengulangan |
| `AgentTest` | Pengurai perintah asisten, penerapan langkah, dan pengaman keluaran model |
| `ReminderTest` | Alarm, mode tahan penghemat baterai (`setAlarmClock`), pelindung duplikat & `EXTRA_DUE`, perubahan jam/izin exact alarm, migrasi v2 → v3 → v4 → v5, transaksi impor SQLite |
| `PerfTest` | Pengukuran waktu bangun indeks, pembaruan satu catatan, pencarian, dan catatan terkait |
| `ShotsLight` / `ShotsDark` / `ShotsTall` | Render layar Compose ke PNG lewat Robolectric + Roborazzi |

Uji Robolectric (`ReminderTest`, `Shots*`) berjalan di lingkungan Linux `glibc` (seperti GitHub Actions CI), sedangkan seluruh uji logika JVM murni & `PerfTest` berjalan langsung baik di CI maupun Termux.
