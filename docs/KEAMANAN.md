# Audit Keamanan & Privasi Catatanku

Dokumen ini merangkum kontrol keamanan, batas ancaman (*threat model*), dan invarian privasi yang diterapkan pada arsitektur `prd-1.8`.

## 1. Invarian Privasi (Zero Exfiltration)

- **100% pemrosesan lokal:** Penyimpanan catatan, riwayat versi, pencarian TF-IDF, indeks vektor semantik (`embeddings`), pengingat, lampiran, dan inferensi AI (`litertlm-android`) berjalan di perangkat.
- **Tanpa SDK tertutup/pelacak:** Tidak ada Google Play Services, Firebase, ML Kit, Ads, Crashlytics, maupun analitik. Diverifikasi otomatis di CI pada graf dependensi `releaseRuntimeClasspath`.
- **Izin jaringan terbatas:** `network_security_config.xml` menetapkan `cleartextTrafficPermitted="false"` untuk semua koneksi. Jaringan hanya dipakai atas permintaan eksplisit pengguna untuk mengunduh model AI dari URL Hugging Face revisi terkunci dengan verifikasi panjang + SHA-256 (`Models.kt`, `LocalAi.kt`). Aplikasi tidak memeriksa pembaruan sendiri maupun meminta izin `REQUEST_INSTALL_PACKAGES`.

## 2. Kunci Aplikasi & Permukaan Layar

- **Autentikasi perangkat:** Memakai `BiometricPrompt` sistem (`MainActivity.kt`) atau `KeyguardManager` pada Android 8–9; Catatanku tidak menyimpan PIN, pola, ataupun data biometrik.
- **Perlindungan layar & papan klip:** Saat kunci aktif, `WindowManager.LayoutParams.FLAG_SECURE` dipasang baik di `MainActivity` maupun `ProcessTextActivity` untuk mencegah tangkapan layar dan pratinjau *Recents*. Salinan ke papan klip ditandai `ClipDescription.EXTRA_IS_SENSITIVE` pada Android 13+ (`UiKit.kt`).
- **Gerbang intent masuk:** Intent eksternal (`ACTION_SEND`, `EXTRA_OPEN`, pintasan, asisten) ditahan di antrean `pendingIntent` hingga autentikasi berhasil atau masa tenggang 60 detik valid (termasuk pengecekan di `onNewIntent`).
- **Notifikasi & token anti-spoof:** Notifikasi pengingat memakai `VISIBILITY_PRIVATE` dan menyembunyikan isi catatan saat kunci aktif. Intent pembukaan catatan dari notifikasi (`EXTRA_OPEN`) wajib menyertakan `EXTRA_TOKEN` acak 128-bit (`SecureRandom` per pemasangan) yang diperiksa secara *constant-time* (`MessageDigest.isEqual`).

## 3. Cadangan Lengkap (`catatanku-backup.zip`) & Enkripsi Opsional

- **Integritas & anti-tamper (`BackupArchive.kt`):**
  - Arsip memuat `manifest.json`, `notes.json`, `attachments/image/*`, `attachments/audio/*`, dan `checksums.json` (SHA-256 untuk setiap entri).
  - Setiap entri diverifikasi lewat daftar putih jalur (`isSafeZipEntryName`): menolak path traversal (`..`, `.`), backslash `\`, null byte, jalur absolut `/`, serta nama lampiran di luar `img_*`/`aud_*`.
  - Perlindungan *ZIP bomb* & JSON berlebih: maksimal 25.000 entri, 64 MB untuk JSON, 32 MB per lampiran, 512 MB total tak terkompresi, serta batas kedalaman & ukuran string di `BoundedJson`.
- **Pemulihan atomik (`NoteRepository.kt`, `NoteDb.kt`):**
  - Ekstraksi dan verifikasi checksum berjalan di direktori *staging* terisolasi di `cacheDir`. Bila gagal, rusak, salah kata sandi, atau dibatalkan, seluruh direktori *staging* dihapus dan basis data tidak tersentuh.
  - Penulisan ke SQLite (`importInTransaction`) dibungkus satu transaksi agar catatan dan riwayat versinya masuk sekaligus atau batal seluruhnya.
- **Enkripsi opsional AES-256-GCM streaming (`BackupCrypto.kt`):**
  - Derivasi kunci memakai `PBKDF2WithHmacSHA256` (120.000 iterasi, salt acak 128-bit per arsip).
  - Mengenkripsi data per blok streaming 64 KB memakai `AES/GCM/NoPadding` (tag 128-bit) dengan nonce unik per blok dan AAD yang mengikat header, indeks blok, serta penanda blok akhir agar pemotongan atau pengacakan blok gagal autentikasi.
  - Kata sandi diterima sebagai `CharArray` dan di-nol-kan (`fill('\u0000')`) beserta buffer kunci `ByteArray` di blok `finally`; sandi dan kunci tidak pernah disimpan ke preferensi ataupun basis data.

## 4. Validasi URI & Media

- **URI eksternal (`InputSecurity.kt`, `Attachments.kt`):** Hanya menerima skema `content://` yang bukan otoritas paket Catatanku (`com.catatanku.app`), menolak `file://`, `userInfo`, maupun otoritas bertitik ganda `..`.
- **Media:** Impor gambar menghapus EXIF via re-encode JPEG; transkode audio membatasi laju sampel (8–192 kHz), kanal (1–8), serta batas waktu 10 detik.

## 5. Batas Prompt & Sanitisasi AI Lokal

- `AiText.sanitizeInput` menghapus karakter null `\u0000` serta token kendali model (`<think>`, `<start_of_turn>`, `<end_of_turn>`, `<|im_start|>`, `<|im_end|>`, `<|endoftext|>`, `<bos>`, `<eos>`) sebelum teks catatan atau ucapan pengguna dimasukkan ke prompt.
- Keluaran model diurai secara defensif (`AiText`, `Agent`): folder hasil halu-AI di luar daftar pengguna ditolak, waktu selalu diurai ulang oleh `TimeParse` deterministik, dan rencana asisten dibatasi maksimal 3 langkah serta selalu menunggu konfirmasi pengguna.

## 6. Batasan yang Diakui Secara Jujur

- Basis data `catatan.db` dan lampiran di `filesDir` mengandalkan enkripsi FBE (*file-based encryption*) bawaan sistem operasi Android (`MODE_PRIVATE`, `android:allowBackup="false"`), bukan enkripsi SQLCipher terpisah, agar tetap ringan tanpa ketergantungan native tambahan dan kompatibel dengan penerima alarm latar belakang sebelum perangkat dibuka kuncinya jika perlu; karena itu fitur **Cadangan Terenkripsi** disediakan untuk melindungi berkas ekspor saat meninggalkan sandbox aplikasi.
