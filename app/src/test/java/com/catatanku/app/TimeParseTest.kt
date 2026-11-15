package com.catatanku.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TimeParseTest {
    /** Minggu, 15 November 2026, 18.30 */
    private val now = LocalDateTime.of(2026, 11, 15, 18, 30)
    private fun at(text: String) = TimeParse.best(text, now)?.at
    private fun dt(mo: Int, d: Int, h: Int, mi: Int = 0, y: Int = 2026) = LocalDateTime.of(y, mo, d, h, mi)

    @Test fun relativeDays() {
        assertEquals(dt(11, 16, 7), at("besok bayar listrik"))
        assertEquals(dt(11, 16, 19), at("Besok jam 7 malam jemput ibu"))
        assertEquals(dt(11, 16, 20), at("besok malam jam 8 nonton"))
        assertEquals(dt(11, 16, 3), at("besok pagi jam 3 berangkat mendaki"))
        assertEquals(dt(11, 17, 18, 30), at("dp 2 juta paling lambat lusa abis maghrib"))
        assertEquals(dt(11, 17, 7), at("lusa ambil paket"))
        assertEquals(dt(11, 22, 7), at("minggu depan kumpul laporan"))
        assertEquals(dt(11, 18, 7), at("3 hari lagi perpanjang SIM"))
        assertEquals(dt(11, 15, 20, 30), at("2 jam lagi minum obat"))
        assertEquals(dt(11, 15, 19), at("nanti malam telepon bapak"))
        assertEquals(dt(11, 15, 21), at("malam ini jam 9 rapat daring"))
    }

    @Test fun explicitDates() {
        assertEquals(dt(11, 24, 10), at("Rapat berikutnya tanggal 24 jam 10 pagi di ruang lantai 2."))
        assertEquals(dt(12, 5, 7), at("tgl 5 bayar kontrakan"))                            // tanggal 5 sudah lewat: bulan depan
        assertEquals(dt(12, 1, 13), at("1 Desember pukul 13.00 kontrol dokter"))
        assertEquals(dt(1, 2, 7, y = 2027), at("2 Januari mulai kerja"))                    // sudah lama lewat: tahun depan
        assertEquals(dt(3, 9, 9, 15, 2027), at("9 Mar 2027 09:15 wawancara"))
        assertEquals(dt(11, 28, 15, 30), at("28/11 jam 15.30 ambil rapor"))
        assertEquals(dt(11, 20, 14), at("Deadline 20-11-2026 pukul 14.00 WIB"))
        assertEquals(dt(1, 1, 7, y = 2027), at("Servis berikutnya di km 27.000 atau awal Januari."))
        assertEquals(dt(11, 30, 7), at("akhir november lunasi cicilan"))
    }

    @Test fun weekdays() {
        assertEquals(dt(11, 20, 15), at("Jumat jam 3 kumpul di kantor"))                    // "jam 3" tanpa keterangan = sore
        assertEquals(dt(11, 20, 7), at("Perbaiki bug login sebelum Jumat"))
        assertEquals(dt(11, 16, 9), at("hari Senin pukul 09.00 upacara"))
        assertEquals(dt(11, 19, 19, 30), at("Kamis malam jam setengah 8 pengajian"))
        assertEquals(dt(11, 22, 10), at("hari minggu jam 10 kondangan"))                    // hari ini Minggu tapi 10.00 sudah lewat
        assertEquals(dt(11, 15, 20), at("hari minggu jam 8 malam arisan"))                  // masih nanti malam
    }

    @Test fun timeOnly() {
        assertEquals(dt(11, 15, 21), at("jam 9 malam matikan pompa"))
        assertEquals(dt(11, 16, 6), at("pukul 06.00 antar anak"))                           // sudah lewat hari ini: besok
        assertEquals(dt(11, 16, 12), at("jam 12 siang makan bersama"))
    }

    @Test fun notATime() {
        assertNull(at("Ganti oli terakhir 3 Oktober di bengkel Jaya Motor, km 24.500."))      // tanggal yang baru lewat
        assertNull(at("Simpang Lima – Pleburan – Tugu Muda, 5 km. Target pace 6:30."))        // angka, bukan janji
        assertNull(at("Bawang putih 3 siung, kecap manis 2 sdm, telur 2."))
        assertNull(at("Anggaran iklan disetujui 5 juta, kerja 2 jam sehari selama seminggu."))
        assertNull(at("hari ini capek sekali"))
        assertNull(at("ikut salat magrib berjamaah di masjid"))
        assertNull(at("rapat kemarin tanggal 14 November 2026 jam 10"))
        assertNull(at(""))
    }

    @Test fun explicitTimeBeatsDateOnly() {
        val found = TimeParse.find("Rapat tim Senin\nBahas rilis.\nRapat berikutnya tanggal 24 jam 10 pagi.", now)
        assertEquals(dt(11, 24, 10), found.first().at)
        assertTrue(found.first().hasTime)
        assertEquals("tanggal 24 jam 10 pagi", found.first().phrase)
        assertEquals(dt(11, 16, 7), found[1].at)
    }

    @Test fun repeats() {
        val start = dt(11, 10, 7)
        assertNull(Repeat.None.next(start, now))
        assertEquals(dt(11, 16, 7), Repeat.Daily.next(start, now))
        assertEquals(dt(11, 17, 7), Repeat.Weekly.next(start, now))
        assertEquals(dt(12, 10, 7), Repeat.Monthly.next(start, now))
        assertEquals(dt(11, 20, 7), Repeat.Daily.next(dt(11, 20, 7), now))                   // belum jatuh tempo: tetap
        // Tanggal 31 tidak bergeser permanen ke 28/30 setelah melewati bulan yang lebih pendek.
        val jan31 = LocalDateTime.of(2026, 1, 31, 8, 0)
        assertEquals(LocalDateTime.of(2026, 3, 31, 8, 0), Repeat.Monthly.next(jan31, LocalDateTime.of(2026, 3, 1, 0, 0)))
        assertEquals(Repeat.Weekly, Repeat.of(2)); assertEquals(Repeat.None, Repeat.of(99))
    }
}
