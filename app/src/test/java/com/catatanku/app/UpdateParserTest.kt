package com.catatanku.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pembacaan jawaban GitHub Releases API; butuh org.json dari Android tiruan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateParserTest {
    private val sha = "a".repeat(64)
    private val url = "https://github.com/papertop221/Catatanku/releases/download/v1.8/Catatanku-1.8.apk"

    private fun json(
        name: String = "Catatanku-1.8.apk",
        digest: String? = "sha256:$sha",
        download: String = url,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String {
        val asset = JSONObject().put("name", name).put("size", 25_000_000L).put("browser_download_url", download)
        if (digest != null) asset.put("digest", digest)
        return JSONObject()
            .put("draft", draft).put("prerelease", prerelease)
            .put("body", "Perbaikan penting.\r\nTerima kasih.")
            .put("assets", JSONArray().put(asset))
            .toString()
    }

    @Test fun picksNewerReleaseWithChecksum() {
        val release = UpdateParser.parse(json(), "1.7")
        assertEquals("1.8", release?.version)
        assertEquals(sha, release?.sha256)
        assertEquals(25_000_000L, release?.bytes)
        assertEquals("Perbaikan penting.\nTerima kasih.", release?.notes)
    }

    @Test fun ignoresSameOrOlderRelease() {
        assertNull(UpdateParser.parse(json(), "1.8"))
        assertNull(UpdateParser.parse(json(), "1.9"))
    }

    @Test fun refusesReleaseWithoutVerifiableChecksum() {
        assertNull(UpdateParser.parse(json(digest = null), "1.7"))
        assertNull(UpdateParser.parse(json(digest = "md5:$sha"), "1.7"))
        assertNull(UpdateParser.parse(json(digest = "sha256:bukan-hex"), "1.7"))
    }

    @Test fun onlyAcceptsOfficialRepositoryAndName() {
        assertNull(UpdateParser.parse(json(download = url.replace("github.com", "github.com.evil")), "1.7"))
        assertNull(UpdateParser.parse(json(download = url.replace("https://", "http://")), "1.7"))
        assertNull(UpdateParser.parse(json(name = "Catatanku-1.8-uji.apk"), "1.7"))
        assertNull(UpdateParser.parse(json(draft = true), "1.7"))
        assertNull(UpdateParser.parse(json(prerelease = true), "1.7"))
    }
}
