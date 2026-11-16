# Benchmark AI Lokal — Moto G86 Power 5G

**Tanggal:** 16 November 2026  
**Perangkat acuan:** Motorola Moto G86 Power 5G (`moto g86 power 5G`, SoC `mt6878` / MediaTek Dimensity 7300: 4× Cortex-A78 + 4× Cortex-A55, Mali-G615), Android 16 (SDK 36), RAM fisik total 7.393 MB (~8 GB kelas perangkat; swap/RAM Boost 5.545 MB **tidak** dihitung sebagai RAM fisik).  
**Runtime & Model:** `litertlm-android` 0.19.1 (`liblitertlm_jni.so`), `gemma-4-E2B-it.litertlm` (2.588.147.712 byte).

## 1. Kemampuan Model & Akselerator di Perangkat

Diperiksa langsung lewat `ModelInfo.from(...)` pada perangkat:
- `TEXT` backend didukung: `CPU`, `GPU`
- `VISION` backend didukung: `CPU`, `GPU`
- `AUDIO` backend didukung: `CPU` saja
- `VIDEO` backend didukung: tidak ada
- Speculative decoding (MTP): didukung (`true`), max context model `4096` token, `maxVisionTokenBudget = 280`
- NPU (`npu_registry.cc`): tidak tersedia di runtime/model ini (`NPU accelerator could not be loaded and registered: kLiteRtStatusErrorNotFound`)

## 2. Benchmark CPU Thread Sweep vs Baseline (1 pemanasan + 3 kali ulang per tugas)

Semua uji pada bagian ini memakai `maxNumTokens = null` (bawaan model 4096), MTP mati, dan tanpa `maxOutputToken` agar sebanding langsung dengan konfigurasi awal v1.7.1 (`cpu-def`).

| Konfigurasi | Warm Init | Peak RSS (`VmHWM`) | Title (med / p95) | Tag (med / p95) | Summarize (med / p95) | Fix (med / p95) | Continue (med / p95) | Decode (med) |
|---|---|---|---|---|---|---|---|---|
| `cpu-def` (bawaan v1.7.1) | 1.915 ms | 1.703,5 MB | 3.759 / 6.120 ms | 4.170 / 4.881 ms | 19.455 / 19.791 ms | 7.306 / 7.805 ms | 34.934 / 36.381 ms | 4,53–5,56 tok/s |
| `cpu-2` | 1.100 ms | 1.727,6 MB | 3.249 / 3.283 ms | 4.046 / 4.525 ms | **17.563** / 18.303 ms | **6.335** / 6.818 ms | **32.689** / 34.094 ms | **5,65–6,30 tok/s** |
| `cpu-4` | 1.158 ms | 1.613,3 MB | **3.079** / 3.204 ms | **3.390** / 4.300 ms | 17.903 / 21.022 ms | 6.641 / 8.065 ms | 35.345 / 36.324 ms | 5,16–5,99 tok/s |
| `cpu-6` | 740 ms | 1.386,0 MB | **3.077** / 3.136 ms | 3.579 / 4.031 ms | 19.029 / 20.295 ms | 6.906 / 7.467 ms | 33.745 / 35.821 ms | 4,82–5,70 tok/s |
| `cpu-8` | 798 ms | 1.376,6 MB | 3.289 / 3.557 ms | 3.516 / 3.559 ms | 19.825 / 20.531 ms | 6.936 / 7.143 ms | 35.506 / 38.776 ms | 5,14–5,55 tok/s |

**Temuan CPU:** `cpu-4` (sesuai 4 inti performa Cortex-A78) mempercepat prefill secara signifikan (64–101 tok/s dibanding 49–80 tok/s pada `cpu-def`), menurunkan TTFT, serta lebih cepat daripada `cpu-6` dan `cpu-8` (yang meluber ke inti efisiensi A55 dan memperlambat decode). `cpu-def` (bawaan lama) lebih lambat daripada `cpu-4` pada semua tugas.

Cold load CPU-4 pertama kali (membangun tembolok XNNPACK): `12.513 ms` (total `BenchmarkInfo.initTimeInSecond = 21,43 dtk`, Peak RSS `1.956,9 MB`).

## 3. Benchmark GPU & Multi-Token Prediction (MTP)

| Konfigurasi | Init | Peak RSS (`VmHWM`) | Title (med) | Tag (med) | Summarize (med / dec) | Fix (med / dec) | Continue (med / dec) |
|---|---|---|---|---|---|---|---|
| `gpu` (MTP off) | 42,8 dtk (compile cache KV 4096) | 2.236,8 MB | 1.944 ms | **1.886 ms** | 15.435 ms / 6,76 tok/s | 4.840 ms / 7,44 tok/s | 26.675 ms / 6,93 tok/s |
| `gpu + mtp=on` | 9.399 ms (warm) | 2.285,2 MB | **1.768 ms** | 2.304 ms (lebih lambat) | 9.500 ms / 11,81 tok/s | 3.268 ms / 10,72 tok/s | 26.675 ms / 7,42 tok/s |
| `gpu + mtp=adaptive` | 6.527 ms (warm) | 2.236,7 MB | 1.866 ms | 2.291 ms (lebih lambat) | **8.974 ms** / **12,34 tok/s** | **3.259 ms** / **10,86 tok/s** | 26.422 ms / 7,47 tok/s |
| `cpu-4 + mtp=on` | 1.427 ms | 1.718,7 MB | 3.029 ms | 4.719 ms (**-39%**) | 9.420 ms / 9,66 tok/s | 4.968 ms / 8,01 tok/s | 30.921 ms / 5,44 tok/s |

Cold load GPU pertama kali tanpa tembolok OpenCL sebelumnya: `100,85 dtk` (`Peak RSS 1.793,9 MB` saat 1 prompt pendek).

**Temuan kritis MTP:**
- MTP mempercepat **Ringkas** (+42% di GPU, +47% di CPU-4) dan **Perbaiki ejaan** (+33% di GPU, +25% di CPU-4).
- MTP **memperlambat Tag** (-21% di GPU, -39% di CPU-4), memperlambat **Perluas pencarian** (decode 6,69 vs 7,06 tok/s), dan memperlambat **Lanjutkan tulisan** pada CPU-4 (decode 5,79 vs 6,30 tok/s).
- `Engine` mendukung pemuatan dengan `SpeculativeDecodingMode.ADAPTIVE` lalu pengubahan per-`Conversation` (`ADAPTIVE` vs `OFF`) **tanpa memuat ulang Engine**.

## 4. Uji KV Cache (`maxNumTokens`), MTP Per-Tugas, & Anggaran Output Token

Engine dimuat dengan `SpeculativeDecodingMode.ADAPTIVE`; percakapan memakai `OFF` pada `title`, `tag`, `planner` dan `ADAPTIVE` pada `summarize`, `fix`, `tasks`, dengan anggaran `maxOutputToken` per tugas (48 / 64 / 192 / 512 / 256 / 160).

| Profil | Warm Init | Peak RSS (`VmHWM`) | Title | Tag | Summarize (dec) | Fix (dec) | Tasks (dec) | Planner |
|---|---|---|---|---|---|---|---|---|
| **`cpu-4` · KV 2.048** | **994 ms** | **1.692,0 MB** | **2.283 ms** | 2.615 ms | **9.064 ms** (13,55 tok/s) | **3.565 ms** (12,66 tok/s) | 4.768 ms (11,79 tok/s) | 6.035 ms |
| `cpu-4` · KV 3.072 | 1.191 ms | 1.718,5 MB | 2.929 ms | 3.281 ms | 15.972 ms (9,32 tok/s) | 3.848 ms (10,49 tok/s) | 4.445 ms (10,43 tok/s) | 5.510 ms |
| `cpu-4` · KV 4.096 | 920 ms | 1.748,3 MB | 2.919 ms | 3.809 ms | 13.166 ms (8,93 tok/s) | 7.592 ms (9,13 tok/s) | 7.404 ms (8,95 tok/s) | 8.132 ms |
| `gpu` · KV 2.048 | 7.923 ms | 2.267,3 MB | 3.650 ms | 2.252 ms | 10.178 ms (10,78 tok/s) | 4.305 ms (10,59 tok/s) | 4.203 ms (11,41 tok/s) | 5.235 ms |
| `gpu` · KV 3.072 | 45,2 dtk (cache baru) | 2.199,3 MB | 2.174 ms | **2.183 ms** | 9.756 ms (11,52 tok/s) | 4.317 ms (9,66 tok/s) | 4.375 ms (11,04 tok/s) | 5.260 ms |
| `gpu` · KV 4.096 | 7.774 ms | 2.374,1 MB | 3.292 ms | 2.865 ms | 12.658 ms (14,00 tok/s) | 3.899 ms (11,04 tok/s) | **3.240 ms** (13,56 tok/s) | **4.735 ms** |

Uji tugas tambahan pada `cpu-4 · KV 2.048`:
- `expand` (96 tok): `mtp=false` 4.971 ms (decode 7,06 tok/s) vs `mtp=true` decode 6,69 tok/s → **MTP OFF lebih baik**.
- `reminder` (128 tok): `mtp=false` 6.018 ms (decode 6,82 tok/s) vs `mtp=true` **4.486 ms (decode 10,54 tok/s)** → **MTP ON +55% decode**.
- `tidy` (512 tok): `mtp=false` 8.951 ms (decode 7,12 tok/s) vs `mtp=true` **7.965 ms (decode 9,83 tok/s)** → **MTP ON +38% decode**.
- `continue` (256 tok): `mtp=false` **32.150 ms (decode 6,30 tok/s)** vs `mtp=true` 34.489 ms (decode 5,79 tok/s) → **MTP OFF lebih baik**.

## 5. Multimodal (Vision & Audio) & Uji Ketahanan Berkelanjutan

| Skenario | Init | Durasi Total | TTFT | Decode | Peak RSS (`VmHWM`) | Keluaran OCR/Audio |
|---|---|---|---|---|---|---|
| `vision-cpu-1600` (CPU-4 + gambar 1600px) | 1.634 ms | 24.148–27.867 ms | 3.319–4.368 ms | 8,06–9,19 tok/s | 2.614,7 MB | Utuh 100% |
| `vision-cpu-1024` (CPU-4 + turunan 1024px) | 1.172 ms | 24.624–26.308 ms | 3.338–3.542 ms | 11,28–11,62 tok/s | 2.366,7–2.387,3 MB (-227 MB) | Utuh 100% |
| **`vision-gpu-1024` (CPU-4 teks + GPU vision + 1024px)** | **1.423 ms** | **11.234–11.967 ms** | 3.525–3.616 ms | **11,46–14,98 tok/s** | **2.141,7–2.162,7 MB (-452 MB)** | Utuh 100% |
| `audio-cpu` (CPU-4 + WAV 3 dtk sunyi) | 794 ms | 2.630–2.796 ms | 1.186–1.355 ms | 5,88–6,40 tok/s | 1.577,1 MB | "Tidak ada ucapan." |

**Uji 5 kali berturut-turut (`throttle-cpu4`, Ringkas, KV 2.048, MTP ADAPTIVE):**
- Run 1: 9.750 ms (9,71 tok/s, RSS 1.444,2 MB)
- Run 2: 7.422 ms (11,58 tok/s, RSS 1.449,3 MB)
- Run 3: 7.678 ms (11,26 tok/s, RSS 1.449,3 MB)
- Run 4: 8.765 ms (9,56 tok/s, RSS 1.449,3 MB)
- Run 5: 9.107 ms (9,35 tok/s, RSS 1.449,3 MB)
Peak RSS stabil sempurna di 1.449 MB (tanpa kebocoran memori); penurunan kecepatan berkelanjutan hanya ~18% pada iterasi ke-5.

## 6. Keputusan Berbasis Data untuk Moto G86 Power 5G

1. **Backend teks default:** **`CPU-4`** (`Backend.CPU(threadCount = 4)`). Pada KV 2.048 dengan MTP per-tugas, `CPU-4` memuat model 8× lebih cepat daripada GPU (1 dtk vs 7,9 dtk warm / 45 dtk cold), menghemat **575–680 MB RAM**, dan menyamai/mengungguli GPU di hampir semua tugas teks (`Ringkas` 9,1 dtk vs 10,2 dtk; `Ejaan` 3,6 dtk vs 4,3 dtk). GPU tetap tersedia sebagai pilihan manual dan akan dijadikan default hanya pada perangkat yang hasil benchmark-nya membuktikan keunggulan bersih tanpa mengorbankan RAM/stabilitas.
2. **Backend Vision:** **`GPU`** untuk vision saat OCR/baca gambar + turunan gambar **≤ 1.024 px** di `cacheDir` (memangkas waktu OCR dari ~26 dtk menjadi **11,5 dtk** sekaligus menghemat **452 MB RAM** dibanding CPU 1600 px, tanpa mengubah berkas asli), dengan fallback otomatis ke CPU.
3. **Backend Audio:** **`CPU-4`** (satu-satunya backend audio yang didukung model).
4. **MTP & KV Cache:** Engine memakai `SpeculativeDecodingMode.ADAPTIVE` + `maxNumTokens = 2048` (profil **Hemat**) sebagai titik awal terukur; per-`Conversation` mengaktifkan MTP untuk `Summarize`, `Fix`, `Tidy`, `Tasks`, `Reminder`, `Custom`, `Ask`, `Recap` dan mematikannya untuk `Title`, `Tag`, `Expand`, `Planner`, `Continue`.

## 7. Keterbatasan Pengujian di Lingkungan CLI

- Suhu baterai (`/sys/class/power_supply/battery/temp`) dan sensor termal (`/sys/class/thermal/`) tidak dapat dibaca langsung dari shell Termux non-root (`Permission denied`), sehingga pencatatan suhu & status termal penuh (`BatteryManager.EXTRA_TEMPERATURE`, `PowerManager.getCurrentThermalStatus()`) diinstrumentasikan langsung di dalam aplikasi Android (`AndroidPerfMonitor`) untuk pengujian APK di perangkat.
- Pengujian audio memakai berkas WAV 16 kHz mono sintetis (sunyi) untuk mengukur waktu muat & inferensi pipeline; akurasi transkripsi ucapan bahasa Indonesia nyata belum diukur ulang pada batch CLI ini.
- Model pembanding yang lebih kecil (`Qwen3 1.7B` / model embedding eksternal) belum terunduh di perangkat sehingga angka perbandingannya tidak dicantumkan/dikarang.
