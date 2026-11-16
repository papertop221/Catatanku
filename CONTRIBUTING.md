# Berkontribusi

Terima kasih sudah mau membantu. Catatanku sengaja kecil dan mudah dibaca; perubahan yang menjaga sifat itu paling mudah diterima.

## Sebelum mulai

- Baca [Arsitektur](docs/ARSITEKTUR.md) untuk struktur kode dan [Desain](docs/DESAIN.md) untuk aturan tampilan.
- Untuk perubahan besar, buka issue dulu supaya arahnya bisa disepakati.

## Menjalankan

```bash
./gradlew assembleDebug        # APK uji, terpasang berdampingan dengan rilis resmi
./gradlew testDebugUnitTest    # semua uji otomatis
./gradlew testDebugUnitTest --tests '*Shots*' -Pshots=true   # render layar ke app/build/shots/
```

Butuh JDK 17+ dan Android SDK 36.

## Pegangan

- **Privasi tidak ditawar.** Tidak ada pelacak, iklan, atau koneksi jaringan selain unduhan model yang diminta pengguna.
- **Tanpa pustaka tertutup.** Aplikasi ini ditujukan untuk F-Droid.
- **AI tidak pernah bertindak sendiri.** Hasilnya ditinjau dulu dan bisa diurungkan.
- **Logika murni dipisah dari Android** agar bisa diuji di JVM; sertakan uji untuk perilaku baru.
- **Tampilan mengikuti DESAIN.md.** Bila aturannya perlu berubah, ubah dokumennya dulu.
- Teks antarmuka dan komentar kode berbahasa Indonesia.

## Melaporkan masalah

Sertakan versi aplikasi, merek HP, versi Android, dan langkah untuk mengulang masalahnya. Aplikasi ini belum banyak diuji
di perangkat sungguhan, jadi laporan dari HP nyata sangat berharga.
