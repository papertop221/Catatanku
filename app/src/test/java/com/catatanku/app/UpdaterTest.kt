package com.catatanku.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionComparison() {
        assertTrue(Versions.isNewer("1.8", "1.7"))
        assertTrue(Versions.isNewer("1.7.1", "1.7"))
        assertTrue(Versions.isNewer("2.0", "1.99.99"))
        assertTrue(Versions.isNewer("1.8", "1.7-uji"))       // akhiran build uji diabaikan
        assertFalse(Versions.isNewer("1.7", "1.7"))
        assertFalse(Versions.isNewer("1.7.0", "1.7"))        // nol di belakang tidak dianggap lebih baru
        assertFalse(Versions.isNewer("1.6.9", "1.7"))
        assertFalse(Versions.isNewer("1.7", "1.7-uji"))
        assertFalse(Versions.isNewer("", "1.7"))
        assertFalse(Versions.isNewer("1.x", "1.7"))
        assertFalse(Versions.isNewer("1.2.3.4.5", "1.7"))
    }

    @Test fun releaseFileName() {
        assertEquals("1.8", UpdateNaming.versionOf("Catatanku-1.8.apk"))
        assertEquals("1.7.2", UpdateNaming.versionOf("Catatanku-1.7.2.apk"))
        assertNull(UpdateNaming.versionOf("catatanku-1.8.apk"))          // nama lain bukan berkas rilis
        assertNull(UpdateNaming.versionOf("Catatanku-1.8.apk.exe"))
        assertNull(UpdateNaming.versionOf("Catatanku-.apk"))
        assertNull(UpdateNaming.versionOf("Catatanku-1.8-uji.apk"))
        assertNull(UpdateNaming.versionOf("Catatanku-1.2.3.4.5.apk"))
    }

    @Test fun onlyTrustedDownloadLinks() {
        assertTrue(UpdateNaming.isTrustedUrl("https://github.com/papertop221/Catatanku/releases/download/v1.8/Catatanku-1.8.apk"))
        assertFalse(UpdateNaming.isTrustedUrl("http://github.com/papertop221/Catatanku/releases/download/v1.8/Catatanku-1.8.apk"))
        assertFalse(UpdateNaming.isTrustedUrl("https://github.com/oranglain/Catatanku/releases/download/v1.8/Catatanku-1.8.apk"))
        assertFalse(UpdateNaming.isTrustedUrl("https://github.com/papertop221/Catatanku/releases/download/v1.8/Catatanku-1.8.apk.evil"))
        assertFalse(UpdateNaming.isTrustedUrl("https://github.com/papertop221/Catatanku/releases/download/v1.8/Catatanku-1.8.apk\n"))
    }

    @Test fun releaseNotesAreBoundedAndClean() {
        assertEquals("Baris satu\nBaris dua", UpdateNaming.cleanNotes("Baris satu\r\nBaris dua\u0000"))
        assertEquals(4000, UpdateNaming.cleanNotes("x".repeat(10_000)).length)
        assertEquals("", UpdateNaming.cleanNotes("\u0000\u0007"))
    }
}
