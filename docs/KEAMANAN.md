# Audit Keamanan & Privasi Catatanku

Dokumen ini merangkum kontrol keamanan berlapis, batas ancaman (*threat model*), dan invarian privasi pada pembaruan keamanan ketat (cabang `keamanan-ketat`).

## 1. Invarian Privasi (Zero Exfiltration)

- **100% pemrosesan lokal:** Penyimpanan catatan, Brankas, riwayat versi, pencarian TF-IDF, indeks vektor semantik (`embeddings`), pengingat, lampiran, dan inferensi AI (`litertlm-android`) berjalan sepenuhnya di perangkat.
- **Tanpa SDK tertutup/pelacak:** Tidak ada Google Play Services, Firebase, ML Kit, Ads, Crashlytics, maupun analitik. Diverifikasi otomatis di CI pada graf dependensi `releaseRuntimeClasspath`.
- **Mode Offline Mutlak (`strict_offline`):** Pengguna dapat mengaktifkan Mode Offline Mutlak di Pengaturan → Privasi untuk memblokir seluruh permintaan unduhan model melalui `DownloadManager` dan membatalkan unduhan berjalan; seluruh fitur aplikasi dan impor model lokal dari berkas tetap berjalan 100%.
- **Nol ekstraksi cloud/perangkat:** `AndroidManifest.xml` menonaktifkan `android:allowBackup="false"`, mengecualikan seluruh domain (`root`, `database`, `sharedpref`, `file`, `external`) pada `fullBackupContent` (`backup_rules.xml`) maupun `dataExtractionRules` Android 12+ (`data_extraction_rules.xml`), sehingga tidak ada data catatan, lampiran, atau kunci yang terbawa oleh cadangan cloud ataupun transfer kabel antarperangkat.
- **Izin jaringan terbatas:** `network_security_config.xml` menetapkan `cleartextTrafficPermitted="false"`. Satu-satunya akses jaringan opsional hanya unduhan model dari URL Hugging Face revisi terkunci dengan verifikasi panjang + SHA-256 (`Models.kt`, `LocalAi.kt`). Aplikasi tidak memiliki self-updater maupun izin `REQUEST_INSTALL_PACKAGES`.

## 2. Enkripsi di Tempat Penyimpanan (Data-at-Rest) & Brankas Tersembunyi

- **Envelope Encryption Android Keystore (`DeviceVaultKey.kt`, `VaultCrypto.kt`):**
  - Kunci pembungkus utama (*KEK*) AES-256-GCM disimpan di `AndroidKeyStore` (`catatanku_vault_kek_v1`, tidak dapat diekspor).
  - Kunci data (*DEK*) 256-bit acak disimpan terbungkus di `noBackupFilesDir/vault_dek.bin` (dikecualikan dari cadangan sistem/ADB).
  - Isi catatan (`title`, `body`, `tags`, `folder`), riwayat versi (`versions.title`, `versions.body`), vektor embedding (`embeddings.vec` & `content_hash`), dan seluruh lampiran (`CTKA\x01`, AES-256-GCM per blok 64 KB dengan AAD terikat nama berkas) dienkripsi di penyimpanan internal (`NoteDb.kt`, `Media.kt`).
  - Data lama (v5 / lampiran polos) didekripsi transparan saat dibaca lalu dimigrasikan ke bentuk terenkripsi secara atomik (`NoteDb.onOpen` & `Attachments.migrateLegacyAttachments`).
- **Brankas Catatan Tersembunyi (`Note.vault`, `NoteView.Vault`):**
  - Catatan Brankas disimpan terenkripsi dengan kolom `vault = 1` dan dipisahkan dari daftar utama, folder publik, blok *Hari ini*, `Stats`, `SearchIndex`, `SemanticIndex`, *Tanya catatan*, *Tugas belum selesai*, *Rekap 7 hari*, *Asisten perintah*, serta ekspor ZIP tak terenkripsi.
  - Membuka tampilan **Brankas** atau notifikasi pengingat milik catatan Brankas selalu menuntut autentikasi ulang, dan langsung mengunci + membersihkan catatan Brankas dari memori ketika keluar dari Brankas, aplikasi ditinggalkan, atau layar HP mati.

## 3. Kunci Aplikasi, Pintu Masuk & Pembersihan Memori

- **Autentikasi kuat & PIN mandiri (`MainActivity.kt`, `AppPinCrypto.kt`, `SecurityPrefs.kt`):**
  - Mendukung `BiometricPrompt` dengan `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (API 30+; `KeyguardManager` pada Android 8–9) serta **PIN/sandi mandiri Catatanku** (PBKDF2WithHmacSHA256 600.000 iterasi, pembanding *constant-time* `MessageDigest.isEqual`, tanpa menyimpan sandi mentah).
  - Perlindungan *brute-force* bertahap (jeda 30 detik pada 5× salah, 2 menit pada 7× salah, 5 menit pada 9× salah) dan opsi **hapus darurat otomatis setelah 10× salah**.
- **Kunci otomatis seketika & pembersihan RAM (`AppViewModel.kt`):**
  - Saat aplikasi terkunci di awal buka (`locked = true`), catatan dan indeks tidak dimuat ke RAM sebelum autentikasi berhasil.
  - Pilihan waktu kunci otomatis: **Segera** (bawaan baru, `0 ms`), 15 detik, atau 1 menit, dengan pengecualian terkendali hanya saat meluncurkan pemilih berkas/dialog izin sistem (`suppressNextLeaveLock`) dan kunci seketika saat layar mati (`Intent.ACTION_SCREEN_OFF`) atau lewat aksi *Kunci sekarang*.
  - Begitu terkunci, seluruh catatan, draf aktif, riwayat versi, indeks pencarian/semantik, hasil tanya/AI, cache kunci DEK di RAM, serta berkas sementara langsung dibersihkan (`scrubSensitiveMemory`, `DeviceVaultKey.clearMemoryCache`).
- **Gerbang intent & `ProcessTextActivity` (`SystemIntegration.kt`):**
  - Menu teks terpilih (`ProcessTextActivity`) ikut menuntut buka kunci saat Kunci Aplikasi aktif sebelum menampilkan opsi atau menyimpan catatan.
  - `pendingIntent` di `MainActivity` tidak tertimpa intent `ACTION_MAIN` kosong; notifikasi pengingat menyembunyikan **judul sekaligus isi** catatan saat kunci aktif atau catatan milik Brankas, dan `EXTRA_OPEN` tetap dilindungi token acak 128-bit `SecureRandom` (*constant-time*).

## 4. Perlindungan Layar, Papan Klip & Keyboard

- **Anti-screenshot & pratinjau Recents:** `FLAG_SECURE` dan `setRecentsScreenshotEnabled(false)` (API 33+) diterapkan baik di `MainActivity` maupun `ProcessTextActivity` ketika perisai layar (`screen_shield`, aktif bawaan), Kunci Aplikasi, atau Brankas aktif.
- **Keyboard penyamaran (`Theme.kt`):** `InterceptPlatformTextInput` menambahkan `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` ke seluruh kolom input agar papan ketik pihak ketiga tidak merekam kata pribadi.
- **Papan klip aman (`UiKit.kt`):** Salinan teks ditandai `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) dan otomatis dihapus dari papan klip setelah 30 detik (API 28+ `clearPrimaryClip()` hanya bila isinya masih sama dengan hash teks yang disalin).

## 5. Cadangan Lengkap (`catatanku-backup.zip`) & AutoBackup Terenkripsi

- **Streaming nol berkas polos sementara (`BackupArchive.kt`, `BackupCrypto.kt`):**
  - Ekspor terenkripsi (`CTKE\x01`, PBKDF2WithHmacSHA256 **600.000 iterasi**, kompatibel mundur 120.000 iterasi) dan pemulihan berjalan streaming per blok 64 KB AES-256-GCM langsung di RAM tanpa menulis `catatanku_enc_*.zip` atau `_decrypted.tmp` polos ke disk.
  - Validasi SHA-256 `checksums.json`, daftar putih jalur ZIP (`isSafeZipEntryName`), batas *ZIP bomb* / JSON (`BoundedJson`), dan pemulihan atomik dalam satu transaksi SQLite.
- **Enkripsi Cadangan Otomatis Hibrida (`CTKH\x01`):**
  - Saat sandi pemulihan disetel, pasangan kunci EC P-256 dibangkitkan: hanya kunci publik disimpan di preferensi untuk mengenkripsi arsip harian (`.enc.zip`) secara otomatis di latar memakai ECDH epiferal + SHA-256 KDF + AES-256-GCM, sementara kunci privat terbungkus sandi pemulihan ikut disematkan di header berkas sehingga dekripsi hanya bisa dilakukan dengan sandi pemulihan pengguna.
  - Bila Kunci Aplikasi aktif tetapi sandi pemulihan belum disetel, Cadangan Otomatis ditahan agar tidak pernah menulis arsip polos ke folder penyimpanan bersama.

## 6. Validasi Masukan, Media, Model AI & Hapus Darurat

- **Media & URI (`Media.kt`, `InputSecurity.kt`):**
  - Validasi `content://` non-internal; teks eksternal disanitasi dari karakter kontrol dan karakter arah Unicode Bidi (`U+202A..U+202E`, `U+2066..U+2069`, `U+200B..U+200F`, `U+FEFF`).
  - Impor gambar menolak *image bomb* (> 32 MB, > 20.000 px/sisi, atau > 100 MP) dan menghapus EXIF lewat re-encode JPEG sebelum dienkripsi (`CTKA\x01`).
  - Seluruh impor suara (termasuk `.wav`) wajib melalui transkode ulang ke WAV 16 kHz mono tanpa berkas mentah fallback; pemutaran suara memakai `MediaDataSource` langsung dari RAM dan inferensi multimodal memakai `Content.ImageBytes` / `Content.AudioBytes` tanpa menulis media polos ke `cacheDir`.
- **Integritas Model & Prompt (`LocalAi.kt`, `AiText.kt`):**
  - Model unduhan terverifikasi dipindahkan ke `filesDir/models/` bila ruang mencukupi; bila tersimpan di direktori eksternal aplikasi, integritas ukuran/stempel waktu/SHA-256 diperiksa ulang sebelum dimuat native JNI. Impor berkas lokal memeriksa `ModelInfo` tipe model sebelum diterima.
  - `AiText.sanitizeInput` membersihkan token kendali LLM modern (termasuk `<|turn|>`, `<|thought|>`, `[INST]`, `<<SYS>>`) serta karakter Bidi/zero-width; instruksi bebas membatasi data catatan dalam delimiter eksplisit.
- **Hapus Darurat (*Panic Wipe*):** Tombol konfirmasi ganda (mengetik `HAPUS`) maupun batas 10× gagal PIN menghancurkan kunci Keystore (*crypto-shredding*), menimpa dan menghapus `catatan.db*`, seluruh lampiran, berkas sementara, dan preferensi keamanan.

## 7. Batasan yang Diakui Secara Jujur

- Saat `ReminderReceiver` berbunyi di latar sebelum layar aplikasi dibuka, ia mengakses `DeviceVaultKey` Android Keystore (tanpa `setUserAuthenticationRequired` agar alarm tetap berbunyi pasca-*boot* setelah perangkat dibuka pertama kali), lalu menyembunyikan judul dan isi catatan pada notifikasi bila Kunci Aplikasi aktif atau catatan milik Brankas.
- Pembacaan memori proses oleh sistem operasi berkedudukan root (*kernel/root memory dump*) saat aplikasi sedang dalam keadaan terbuka kuncinya berada di luar batas ancaman aplikasi ruang pengguna Android.
