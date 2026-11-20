package com.schwabquant.app.core.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class OccSymbolFormatterTest {

    private val formatter = OccSymbolFormatter()

    @Test
    fun `formats NVDA and IWM examples matching SCHWAB_API_REFERENCE`() {
        val nvdaCall = formatter.format(
            root = "NVDA",
            expiration = LocalDate.of(2028, 1, 21),
            isCall = true,
            strike = BigDecimal("165")
        )
        assertEquals(21, nvdaCall.length)
        assertEquals("NVDA  280121C00165000", nvdaCall)

        val iwmCall = formatter.format(
            root = "IWM",
            expiration = LocalDate.of(2026, 12, 18),
            isCall = true,
            strike = BigDecimal("284")
        )
        assertEquals(21, iwmCall.length)
        assertEquals("IWM   261218C00284000", iwmCall)
    }

    @Test
    fun `parses 21-char OCC symbol back into components`() {
        val parsed = formatter.parse("IWM   280121C00220000")
        assertNotNull(parsed)
        assertEquals("IWM", parsed?.root)
        assertEquals(LocalDate.of(2028, 1, 21), parsed?.expiration)
        assertEquals(true, parsed?.isCall)
        assertEquals(0, BigDecimal("220.000").compareTo(parsed?.strike))
    }

    @Test
    fun `H-1 rejects leading spaces, empty root, signed or zero strikes, and invalid calendar dates`() {
        // Leading spaces in root
        assertNull(formatter.parse("  NVDA280121C00165000"))
        // Internal space in root
        assertNull(formatter.parse("NV DA 280121C00165000"))
        // All-spaces empty root
        assertNull(formatter.parse("      280121C00165000"))
        // Lowercase root
        assertNull(formatter.parse("nvda  280121C00165000"))
        // Negative / signed / zero strike
        assertNull(formatter.parse("NVDA  280121C-0165000"))
        assertNull(formatter.parse("NVDA  280121C+0165000"))
        assertNull(formatter.parse("NVDA  280121C00000000"))
        // Strict date resolver rejects Feb 31 (no silent rollover to Feb 29)
        assertNull(formatter.parse("NVDA  280231C00165000"))
        assertNull(formatter.parse("NVDA  281301C00165000"))

        // format() rejects zero/negative strike and punctuation roots
        assertThrows(IllegalArgumentException::class.java) {
            formatter.format("NVDA", LocalDate.of(2028, 1, 21), true, BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            formatter.format("BRK.B", LocalDate.of(2028, 1, 21), true, BigDecimal("450"))
        }
    }
}
