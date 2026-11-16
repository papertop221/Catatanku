# Filosofi dan Standar Wajib Pengembangan Catatanku

Dokumen ini menetapkan jati diri Catatanku dan standar teknis yang **wajib dijalankan** oleh setiap pengembang maupun asisten AI (Claude, Gemini, Codex, Cursor, Copilot, dan lainnya).

Setiap usulan fitur, perbaikan bug, refactor, atau optimasi harus lulus **kelima pilar filosofi** di bawah ini sebelum boleh ditulis atau digabungkan ke repositori.

---

## Lima Pilar Catatanku

> **Praktis · Efektif & Efisien · Canggih · 100% Privasi · Aman**

### 1. Praktis — *Langsung pakai, tanpa hambatan*

Mencatat harus terasa seringan membuka buku saku dan menulis: cepat dibuka, langsung fokus ke isi, dan tidak memaksa pengguna mempelajari menu rumit.

**Aturan wajib:**
- **Alur sesingkat mungkin.** Pengguna langsung berada di daftar catatan, kolom cari, atau tombol buat catatan baru; tidak ada layar selamat datang bertele-tele, tab berlapis, atau formulir panjang.
- **Tidak perlu tombol simpan.** Editor menyimpan otomatis setiap jeda mengetik dan selalu menyediakan **Urungkan / Ulangi**. Aksi menghapus atau mengarsipkan wajib menyediakan **Urungkan**.
- **Interaksi langsung pada konten.** Kotak centang (`☐` / `☑`) dapat diketuk langsung untuk menandai selesai; daftar centang dan poin otomatis berlanjut saat menekan *Enter*.
- **Menyatu dengan alur kerja HP.** Mendukung kiriman teks/gambar/suara dari aplikasi lain, menu teks terpilih (`ProcessTextActivity`), Tile Setelan Cepat (`QuickNoteTile`), pintasan ikon, serta pengingat sekali kalimat.
- **Bahasa antarmuka lugas dan manusiawi.** Seluruh teks aplikasi memakai bahasa Indonesia yang jelas; tombol memakai kata kerja + objek (*"Simpan pengingat"*, *"Jalankan rencana"*, *"Hapus model"*), bukan label samar seperti *"OK"*.

---

### 2. Efektif & Efisien — *Ringan di HP spek rendah, nihil pemborosan*

Setiap fitur harus benar-benar menyelesaikan kebutuhan nyata tanpa membebani RAM, CPU, baterai, penyimpanan, maupun layar. Aplikasi harus tetap responsif bahkan di HP dengan RAM pas-pasan dan penyimpanan lambat.

**Aturan wajib:**
- **Kode kecil, native, dan tanpa bloat.** Kotlin + Jetpack Compose dengan arsitektur satu modul dan minimal dependensi eksternal; hindari pustaka navigasi, DI, atau framework berat.
- **Jalur mengetik dan gulir daftar harus murah.** Dilarang melakukan rebuild indeks pencarian penuh, statistik global (`Stats`), pembacaan/penulisan file sinkron, atau alokasi besar di UI thread setiap ketikan. Tunda komputasi daftar/indeks selama pengguna aktif menyunting (`deferList` → `flushListIfStale`).
- **Hemat RAM untuk gambar, audio, dan cadangan.**
  - Baca gambar dan metadata media secara asinkron di `Dispatchers.IO` (`produceState`), pakai *subsampling* sesuai kebutuhan layar dan konfigurasi hemat memori (`RGB_565` / `ALLOCATOR_SOFTWARE`).
  - Penulisan JSON cadangan/auto-backup dan rekaman audio wajib memakai *streaming* (`JsonWriter`, `BufferedOutputStream`), bukan menumpuk string atau buffer raksasa di RAM.
- **Transaksi database yang efisien.** Pembaruan banyak baris (misalnya ubah/bubar folder) dijalankan dalam satu transaksi SQLite di `Dispatchers.IO`, bukan loop baca-tulis berulang.
- **Sadari batas memori model AI.** Jangan memuat model saat aplikasi dibuka atau di proses latar tanpa perintah pengguna; hormati `lightMode`, cek sisa RAM sebelum memuat model (beserta margin aman untuk mode gambar/suara), bebaskan memori ketika menganggur atau ditinggalkan, dan sediakan *fallback* otomatis GPU → CPU.
- **Desain "Kertas & Tinta".** Ikuti [DESAIN.md](DESAIN.md): tanpa gradasi, bayangan berat, atau animasi berlebihan; latar gelap hitam pekat `#000000` agar hemat daya di layar AMOLED.

---

### 3. Canggih — *Kecerdasan nyata yang terintegrasi, bukan gimmick*

Kecanggihan Catatanku hadir untuk mengubah catatan mentah menjadi tindakan nyata langsung di perangkat—bukan sekadar menempelkan chatbot atau hiasan visual bertema AI.

**Aturan wajib:**
- **AI lokal yang benar-benar berguna dan terintegrasi sistem.** Membantu meringkas, memperbaiki ejaan, mengekstrak daftar tugas nyata (termasuk dua langkah terstruktur untuk gambar dan rekaman suara agar hanya tugas nyata yang diambil, bukan deskripsi pasif), menjawab pertanyaan dari catatan sendiri, dan menjalankan asisten perintah (maksimal 3 langkah terencana)—baik di dalam editor maupun langsung dari menu teks terpilih aplikasi lain.
- **Gabungkan determinisme kode dengan AI.** Pekerjaan yang menuntut presisi mutlak dan kecepatan instan—seperti penghitungan tanggal/waktu (`TimeParse`), pencarian toleran imbuhan & salah ketik (`SearchEngine` TF-IDF + trigram), dan struktur data—wajib dikerjakan oleh kode deterministik, bukan diserahkan ke LLM.
- **Pengguna selalu memegang kendali penuh.** AI tidak pernah bertindak diam-diam: hasilnya ditampilkan lebih dulu untuk ditinjau, diterapkan atas persetujuan pengguna, dan selalu dapat diurungkan.
- **AI adalah cara kerja, bukan maskot.** Tidak ada ikon kilau (`✨`), lencana *"AI Powered"*, atau layar khusus chatbot.

---

### 4. 100% Privasi — *Data milik pengguna sepenuhnya, nol kebocoran*

Catatan pribadi berisi pikiran, rencana, suara, dan gambar milik pengguna. Catatanku dibangun dengan prinsip *local-first* dan *offline-first* mutlak.

**Aturan wajib:**
- **Nol server, nol akun, nol telemetri.** Tidak ada akun login, cloud sync pihak ketiga, analitik, pelacak, iklan, ataupun laporan crash eksternal. Cadangan cloud bawaan Android (`allowBackup`) tetap dinonaktifkan.
- **Satu-satunya akses jaringan yang diizinkan**, tanpa akun dan tanpa mengirim data pengguna:
  - Mengunduh berkas model AI open-source atas permintaan eksplisit pengguna melalui `DownloadManager` (dengan
    verifikasi SHA-256 pada katalog `Models.kt`).
  Aplikasi harus tetap berfungsi penuh 100% meskipun izin `INTERNET` dihapus (impor berkas model lokal tetap berjalan).
- **Tanpa self-updater (patuh F-Droid).** Aplikasi tidak memeriksa maupun memasang pembaruan APK sendiri dari GitHub; izin `REQUEST_INSTALL_PACKAGES` tidak dipakai, sehingga build resmi langsung memenuhi kebijakan F-Droid.
- **100% pustaka sumber terbuka (siap F-Droid).** Dilarang keras memasukkan Google Play Services, Firebase, ML Kit, AdMob, atau SDK tertutup apa pun.
- **Prinsip izin minimum (*least privilege*).** Dilarang meminta izin invasif yang tidak esensial (misalnya `AccessibilityService`, membaca daftar aplikasi/kontak penuh, atau lokasi terus-menerus).

---

### 5. Aman — *Pertahanan berlapis di seluruh permukaan aplikasi*

Privasi tanpa keamanan teknis mudah ditembus lewat file berbahaya, celah antar-komponen Android, atau manipulasi input. Keamanan harus menjadi bagian bawaan setiap fitur baru.

**Aturan wajib:**
- **Tegakkan kunci aplikasi di semua pintu masuk.**
  - Setiap `PendingIntent` pembuka catatan dari notifikasi/luar wajib membawa token rahasia acak per-instalasi (`SecureRandom`, diperiksa secara *constant-time* dengan `MessageDigest.isEqual`) dan ditunda bila kunci aplikasi sedang aktif sampai pengguna membuka kunci (`shouldDeferForLock` / `replayPendingIntent`).
  - Layar cepat eksternal yang menampilkan/mengolah catatan (`ProcessTextActivity`) wajib memasang `FLAG_SECURE` saat kunci aktif agar isi catatan tidak bocor lewat screenshot atau *Recent Apps*.
  - Teks yang disalin saat kunci aktif wajib ditandai sensitif (`ClipDescription.EXTRA_IS_SENSITIVE` pada API 33+).
- **Perlakukan semua input luar sebagai data tak tepercaya (*untrusted*).**
  - Saring URI eksternal (`isSafeExternalUri`): hanya terima skema `content://` dari luar otoritas aplikasi sendiri; tolak `file://` mentah atau URI internal aplikasi.
  - Beri batas tegas pada impor/cadangan JSON (`MAX_IMPORT_BYTES`, batas jumlah catatan, panjang kolom, timestamp non-negatif, dan validasi pola nama lampiran yang aman terhadap *path traversal*), serta batasi panjang teks masuk dari luar.
  - Lindungi AI lokal dari *prompt injection*: bersihkan teks catatan, teks terpilih, maupun transkrip sebelum masuk ke prompt menggunakan `AiText.sanitizeInput` (hapus karakter kontrol nol & token template model).
- **Tolak lalu lintas jaringan tak terenkripsi.** `networkSecurityConfig` wajib menjaga `cleartextTrafficPermitted="false"`.
- **Lindungi keutuhan data pengguna dan kunci tanda tangan.** Perubahan skema SQLite wajib memiliki jalur migrasi aman (`onUpgrade` + uji migrasi). Berkas kunci rilis (`keystore.properties`, `*.jks`, `*.keystore`) dilarang keras masuk Git atau ditampilkan dalam log/kode.

---

## Daftar Periksa Wajib Sebelum Menyelesaikan Perubahan

Setiap asisten AI dan kontributor **wajib** memastikan semua butir di bawah ini terjawab **"Ya"** sebelum menganggap tugas selesai:

1. **Praktis:** Apakah alurnya ringkas, konsisten dengan `DESAIN.md`, bisa diurungkan, dan berbahasa Indonesia jelas?
2. **Efektif & Efisien:** Apakah aman untuk HP RAM rendah/CPU lambat (tanpa kerja berat di jalur ketik/UI thread, media & cadangan streaming/subsampled, tanpa dependensi/bloat baru)?
3. **Canggih:** Apakah fitur baru benar-benar membantu pengguna secara nyata/lokal tanpa gimmick dan tetap memakai kode deterministik untuk waktu/pencarian/struktur?
4. **100% Privasi:** Apakah bebas akun, bebas pelacak, bebas SDK tertutup, dan tidak mengirim data ke luar perangkat?
5. **Aman:** Apakah pintu masuk Intent/URI/file/clipboard/prompt sudah disaring dan menghormati status kunci aplikasi?
6. **Teruji & Jujur:** Apakah logika murni tetap bebas impor `android.*`, tes terkait sudah ditambah/dijalankan (`testDebugUnitTest`, `PerfTest` bila menyentuh pencarian/jalur panas, `assembleRelease` untuk cek R8), dan batasan pengujian lingkungan (misalnya Termux vs HP nyata) dilaporkan secara jujur?
