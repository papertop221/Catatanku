# Desain: "Kertas & Tinta"

Aturan tampilan Catatanku. Wujud kodenya ada di `Theme.kt` (warna, huruf, bentuk, ikon) dan `UiKit.kt` (komponen bersama).
Perubahan tampilan sebaiknya mengubah berkas ini dulu, baru kodenya.

## Prinsip

1. **Isi di depan, kontrol di belakang.** Layar adalah alat kerja, bukan halaman promosi.
2. **Setiap hiasan harus punya tugas.** Warna, garis, dan gerak dipakai hanya bila menyampaikan sesuatu.
3. **Satu aksi utama per layar atau lembar.** Sisanya aksi teks.
4. **AI adalah cara kerja, bukan maskot.** Tidak ada warna, ikon kilau, atau layar khusus untuk AI.

## Warna

| Peran | Terang | Gelap | Dipakai untuk |
|---|---|---|---|
| Kertas | `#FBFBFA` | `#000000` | Latar. Gelap = hitam pekat (piksel mati di layar AMOLED) |
| Tinta | `#191918` | `#ECECE9` | Teks, ikon, tombol utama |
| Tinta redup | `#5F5E5A` | `#A6A5A0` | Keterangan, meta |
| Garis | `#E6E5E1` | `#2A2A28` | Pemisah baris |
| Amber | `#8A5200` | `#F0B450` | **Waktu saja**: pengingat, tenggat, blok "Hari ini" |
| Merah | `#B3261E` | `#F28B82` | Yang terlewat dan tindakan yang menghapus |

Tidak ada gradasi. Warna mengikuti wallpaper tersedia sebagai pilihan di Pengaturan (Android 12+), bukan bawaan.

## Huruf

- **Isi catatan** (judul, isi, baris daftar): Atkinson Hyperlegible Next, satu berkas variabel (115 KB, lisensi OFL).
  Dirancang agar huruf yang mirip mudah dibedakan; angka nolnya bergaris miring.
- **Antarmuka** (tombol, label, menu): font sistem.

## Bentuk dan permukaan

- Dua radius: 8 dp (tombol, kolom, chip) dan 16 dp (lembar bawah, dialog).
- Daftar berupa baris datar dengan garis pemisah. Tidak ada kartu, bayangan, atau efek kaca.
- Sasaran sentuh minimal 44–48 dp.

## Jarak (mulai 1.10)

Satu ritme 4 dp; nilai di luar daftar ini tidak dipakai.

| Token | Nilai | Dipakai untuk |
|---|---|---|
| Tepi (`Gutter`) | 20 dp | Jarak kiri-kanan semua isi layar dan lembar |
| Baris | 12 dp atas & bawah | Baris catatan di daftar; baris Pengaturan minimal 52 dp |
| Kelompok | 28 dp | Jarak sebelum judul kelompok (Pengaturan, lembar) |
| Rapat | 4–8 dp | Antara judul dan keterangannya, antar-chip |

Judul kelompok ditulis kapital kecil (`labelSmall`, jarak huruf lebar), tanpa garis pemisah di atasnya:
jarak kosonglah yang memisahkan kelompok.

## Ikon

Satu set garis buatan sendiri di `AppIcons` (24 dp, tebal 1,75, ujung membulat). Tidak ada emoji.

Ikon hanya dipakai di **bilah atas** dan **bilah alat editor**, berdiri sendiri tanpa label, dan selalu punya
keterangan untuk pembaca layar. Di tempat lain (menu, lembar, Pengaturan, judul lembar, layar kunci) cukup kata,
tanpa ikon pendamping. Satu-satunya ikon di tengah teks adalah lonceng kecil pada waktu pengingat.

## Tata letak

- **Satu layar utama, tanpa tab.** Atas: tampilan yang dipilih. Tengah: blok "Hari ini" (hanya bila ada isinya),
  lalu daftar. Bawah, sejangkauan ibu jari: filter tag, kolom cari, tombol catatan baru.
- **Jawaban tampil di atas daftar**, bukan di layar tersendiri.
- **Geser baris**: kanan = arsipkan, kiri = pengingat. Aksi yang sama ada di menu tekan-lama.
- **Editor**: tanpa tombol simpan; penanda "Tersimpan" di bilah atas. Alat menempel di atas papan ketik.

## Bahasa

- Satu hal, satu nama, di seluruh aplikasi. Nama fitur singkat dan baku (lihat glosarium di bawah).
- Keterangan di bawah judul baris hanya untuk **keadaan** (galat, status yang perlu tindakan), bukan untuk
  menjelaskan fitur. Penjelasan dan peringatan tinggal di dialog konfirmasi.
- Tombol = kata kerja + objek: "Simpan pengingat", "Jalankan rencana", "Hapus model". Bukan "OK" atau "Ya".
- Keadaan kosong menyebut langkah berikutnya.
- Pesan galat menyebut apa yang terjadi dan apa yang bisa dilakukan.
- Tindakan yang menyingkirkan catatan selalu menawarkan "Urungkan".

### Glosarium nama fitur (mulai 1.10)

| Nama | Menggantikan |
|---|---|
| Asisten | Bantu tulis, Fitur AI |
| Lanjutkan · Koreksi ejaan · Susun ulang · Ringkasan · Ekstrak tugas · Buat judul · Instruksi khusus | Lanjutkan tulisan, Perbaiki ejaan, Rapikan struktur, Ringkas, Ambil daftar tugas, Instruksi bebas |
| Lengkapi metadata | Isi judul, folder & tag (yang kosong) |
| Deteksi jadwal | Sarankan pengingat |
| Ekstrak teks · Transkripsi | Baca gambar, Transkripsikan |
| Tugas terbuka · Ringkasan mingguan | Tugas belum selesai, Rekap 7 hari |
| Tanyakan · Perluas pencarian · Jalankan | Tanya catatan, Jalankan perintah |
| Kunci otomatis · PIN aplikasi · Hapus setelah 10 kali gagal · Hapus semua data | (rincian keamanan) |
| Lindungi layar · Keyboard privat · Bersihkan papan klip · Mode offline | Blokir tangkapan layar, Keyboard penyamaran, Hapus papan klip 30 dtk, Mode Offline Mutlak |
| Model bahasa · Pencarian semantik · Kapasitas konteks · Kalibrasi kinerja | Model AI, Pencarian makna, Panjang bacaan, Penyesuaian kecepatan |
| Izin notifikasi · Alarm presisi · Optimasi baterai · Prioritas alarm · Diagnostik | (rincian pengingat) |
| Warna dinamis | Warna mengikuti wallpaper |

## Keadaan yang harus selalu didesain

Memuat (kerangka abu-abu), kosong, tidak ada hasil, galat, izin ditolak, model belum ada, model sedang dimuat,
RAM tidak cukup, dan unduhan berjalan.

## Yang sengaja dihindari

Gradasi ungu-biru, efek kaca, kartu bersudut besar berbayang, garis tebal berwarna di satu sisi kartu, emoji sebagai
ikon, lencana "Baru"/"Live", animasi masuk di tiap elemen, dan ikon kilau untuk AI.
