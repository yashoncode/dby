package com.dby.mobile

import com.dby.mobile.update.parseManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestTest {
    private val manifest = """
        {
          "package": {"downloadUrl": "https://example.com/dby-1.1.0.apk", "downloadSize": 17000000, "sha256": "ab12"},
          "1.0.0": {"versionCode": 100, "changelog": ["First release"]},
          "1.1.0": {"versionCode": 110, "changelog": ["Faster grid", "Fix export"]},
          "0.1.0": {"versionCode": 10, "changelog": ["Preview"]}
        }
    """.trimIndent()

    @Test
    fun newest_version_wins_with_its_package() {
        val r = parseManifest(manifest, installed = 100)!!
        assertEquals("1.1.0", r.versionName)
        assertEquals(110L, r.versionCode)
        assertEquals("https://example.com/dby-1.1.0.apk", r.url)
        assertEquals(17_000_000L, r.size)
        assertEquals("ab12", r.sha256)
        assertEquals(listOf("Faster grid", "Fix export"), r.notes)
    }

    @Test
    fun notes_cover_every_version_newer_than_installed_newest_first() {
        assertEquals(listOf("Faster grid", "Fix export", "First release"), parseManifest(manifest, installed = 10)!!.notes)
    }

    @Test
    fun up_to_date_is_null() {
        assertNull(parseManifest(manifest, installed = 110))
        assertNull(parseManifest(manifest, installed = 200))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missing_package_is_an_error() {
        parseManifest("""{"1.0.0": {"versionCode": 100}}""", installed = 1)
    }
}
