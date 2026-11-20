package com.schwabquant.app.core.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppVersionFormatterTest {

    @Test
    fun `formats V1_4 when patch is zero and includes patch when positive`() {
        val v14 = AppVersion(major = 1, minor = 4, patch = 0, code = 14)
        assertEquals("1.4", v14.versionName)
        assertEquals("V1.4", v14.versionLabel)
        assertEquals("V1.4 (Build 14)", v14.displayLabel)

        val v141 = AppVersion(major = 1, minor = 4, patch = 1, code = 15)
        assertEquals("1.4.1", v141.versionName)
        assertEquals("V1.4.1", v141.versionLabel)
        assertEquals("V1.4.1 (Build 15)", v141.displayLabel)
    }

    @Test
    fun `bumps minor, patch, and major while keeping versionCode monotonically increasing`() {
        val v14 = AppVersion(major = 1, minor = 4, patch = 0, code = 14)

        val v15 = v14.bump(VersionBumpType.MINOR)
        assertEquals(AppVersion(1, 5, 0, 15), v15)
        assertEquals("1.5", v15.versionName)

        val v19 = AppVersion(major = 1, minor = 9, patch = 2, code = 21)
        val v110 = v19.bump(VersionBumpType.MINOR)
        assertEquals(AppVersion(1, 10, 0, 22), v110)
        assertEquals("V1.10", v110.versionLabel)

        val v141 = v14.bump(VersionBumpType.PATCH)
        assertEquals(AppVersion(1, 4, 1, 15), v141)
        assertEquals("V1.4.1", v141.versionLabel)

        val v20 = v141.bump(VersionBumpType.MAJOR)
        assertEquals(AppVersion(2, 0, 0, 16), v20)
        assertEquals("V2.0", v20.versionLabel)
    }

    @Test
    fun `round-trips version properties text and rejects invalid inputs`() {
        val raw = """
            VERSION_MAJOR=1
            VERSION_MINOR=4
            VERSION_PATCH=0
            VERSION_CODE=14
        """.trimIndent()
        val parsed = AppVersionFormatter.parse(raw)
        assertEquals(AppVersion(1, 4, 0, 14), parsed)

        val serialized = parsed.bump(VersionBumpType.MINOR).toPropertiesString()
        val reparsed = AppVersionFormatter.parse(serialized)
        assertEquals(AppVersion(1, 5, 0, 15), reparsed)

        assertEquals(VersionBumpType.MINOR, AppVersionFormatter.parseBumpType(null))
        assertEquals(VersionBumpType.PATCH, AppVersionFormatter.parseBumpType("patch"))
        assertEquals(VersionBumpType.MAJOR, AppVersionFormatter.parseBumpType("MAJOR"))
        assertThrows(IllegalStateException::class.java) {
            AppVersionFormatter.parseBumpType("alpha")
        }
    }
}
