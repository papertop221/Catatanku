# Berkontribusi

Terima kasih sudah mau membantu. Catatanku dibangun di atas lima pilar filosofi: **Praktis, Efektif & Efisien, Canggih, 100% Privasi, dan Aman**. Perubahan yang menjaga sifat itu paling mudah diterima.

## Sebelum mulai

- **Wajib:** Baca [Filosofi dan Standar Pengembangan](docs/FILOSOFI.md) agar sejalan dengan lima pilar program.
- Baca [Arsitektur](docs/ARSITEKTUR.md) untuk struktur kode dan [Desain](docs/DESAIN.md) untuk aturan tampilan.
- Lihat [Peta jalan](docs/ROADMAP.md) untuk pekerjaan berikutnya dan keputusan yang sudah diambil.
- Memakai asisten AI? Arahkan ke [AGENTS.md](AGENTS.md) dan [docs/FILOSOFI.md](docs/FILOSOFI.md).
- Untuk perubahan besar, buka issue dulu supaya arahnya bisa disepakati.

## Menjalankan

```bash
./gradlew assembleDebug        # APK uji, terpasang berdampingan dengan rilis resmi
./gradlew testDebugUnitTest    # semua uji otomatis
./gradlew testDebugUnitTest --tests '*PerfTest*' -Pperf=true -i   # bila menyentuh pencarian/jalur panas
./gradlew testDebugUnitTest --tests '*Shots*' -Pshots=true       # render layar ke app/build/shots/
```

Butuh JDK 17+ dan Android SDK 36.

## Pegangan wajib (5 Pilar)

- **Praktis:** Alur singkat, simpan otomatis tanpa tombol Simpan, dan tindakan menyingkirkan/AI selalu bisa diurungkan.
- **Efektif & Efisien:** Wajib ringan di HP berspesifikasi rendah; tanpa dependensi berat, tanpa kerja berat di UI thread atau jalur mengetik, dekode media asinkron/subsampled, serta cadangan streaming.
- **Canggih:** AI lokal yang benar-benar membantu tanpa gimmick atau layar chatbot terpisah; tanggal/waktu (`TimeParse`) dan pencarian (`SearchEngine`) tetap memakai kode deterministik.
- **100% Privasi:** Tanpa pelacak, iklan, akun, server, atau pustaka tertutup (syarat F-Droid); jaringan hanya dipakai untuk unduhan model yang diminta pengguna.
- **Aman:** Hormati kunci aplikasi di semua pintu masuk, perlakukan semua input eksternal (URI, JSON, teks luar, prompt) sebagai tidak tepercaya dan saring terlebih dahulu.
- **Kode bersih & teruji:** Logika murni dipisah dari Android agar bisa diuji di JVM; sertakan uji untuk perilaku baru. Teks antarmuka dan komentar kode berbahasa Indonesia, mengikuti [DESAIN.md](docs/DESAIN.md).

## Melaporkan masalah

Sertakan versi aplikasi, merek HP, versi Android, dan langkah untuk mengulang masalahnya. Aplikasi ini belum banyak diuji
di perangkat sungguhan, jadi laporan dari HP nyata sangat berharga.
