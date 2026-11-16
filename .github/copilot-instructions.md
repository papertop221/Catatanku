# Panduan Wajib AI dan Kontributor — Catatanku

Baca dan jalankan seluruh isi [AGENTS.md](../AGENTS.md) serta [docs/FILOSOFI.md](../docs/FILOSOFI.md) sebelum mengubah apa pun.

Ringkasnya, setiap perubahan **wajib** memenuhi 5 pilar filosofi Catatanku:
1. **Praktis** — alur ringkas, simpan otomatis + Urungkan, interaksi langsung, bahasa Indonesia manusiawi.
2. **Efektif & Efisien** — sangat ringan di HP spek rendah; tanpa bloat, tanpa kerja berat di jalur mengetik/UI thread, dekode media asinkron/subsampled, cadangan & audio streaming, transaksi SQLite, cek RAM & fallback CPU untuk AI.
3. **Canggih** — AI lokal terintegrasi nyata (bukan gimmick visual/chatbot); waktu (`TimeParse`) & pencarian (`SearchEngine`) dikerjakan oleh kode deterministik.
4. **100% Privasi** — offline-first, nol akun/server/analitik/iklan/pelacak, 100% pustaka sumber terbuka; jaringan hanya untuk mengunduh model AI atas permintaan pengguna.
5. **Aman** — kunci aplikasi ditegakkan di semua pintu masuk (`PendingIntent` bertoken, tunda saat terkunci, `FLAG_SECURE`, `EXTRA_IS_SENSITIVE`), semua input luar disaring (`isSafeExternalUri`, batas impor JSON, `AiText.sanitizeInput`), tanpa trafik polos (`cleartextTrafficPermitted="false"`).
