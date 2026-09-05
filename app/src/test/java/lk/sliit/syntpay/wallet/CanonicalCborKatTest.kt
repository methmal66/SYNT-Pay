package lk.sliit.syntpay.wallet

import lk.sliit.syntpay.shared.CanonicalCbor
import lk.sliit.syntpay.shared.SpendEntry
import lk.sliit.syntpay.shared.hexToBytes
import lk.sliit.syntpay.shared.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The known-answer test vectors of SYNTPAY_PROJECT.md section 5.
 *
 * These come from an independent Python reference implementation. Both apps
 * must reproduce them exactly. A failure here means everything downstream is
 * worthless: if the two encoders differ by one byte, one payment produces two
 * receipt identifiers and the merchant is credited twice, silently.
 */
class CanonicalCborKatTest {

    private fun vector(): SpendEntry = SpendEntry(
        index = 0,
        txnId = hexToBytes("000102030405060708090a0b0c0d0e0f"),
        amountMinor = 2500L,
        batchId = "batch-0001",
        prevHash = ByteArray(32),
        walletId = "wallet-0001",
        merchantId = "Org1MSP",
        terminalId = "term-0001",
        voucherRef = CanonicalCbor.sha256("voucher".toByteArray(Charsets.UTF_8)),
        terminalTime = 1_750_000_000L,
    )

    @Test
    fun encodedLengthIs227Bytes() {
        assertEquals(227, CanonicalCbor.encode(vector()).size)
    }

    @Test
    fun encodingPrefixMatchesTheReferenceImplementation() {
        val expected =
            "aa65696e646578006574786e494450000102030405060708090a0b0c0d0e0f66616d6f756e741909"
        assertEquals(expected, CanonicalCbor.encode(vector()).toHex().substring(0, expected.length))
    }

    @Test
    fun receiptIdMatchesTheReferenceImplementation() {
        assertEquals(
            "1c17f4837955a32baba235953cc36723de98496c204241d5ce0bcf6c7a34d016",
            CanonicalCbor.receiptId(vector()).toHex(),
        )
    }

    /** Smallest-possible integer encoding, RFC 8949 section 4.2.1. */
    @Test
    fun integersUseTheShortestEncoding() {
        val entry = vector()
        fun amountBytes(amount: Long): String {
            val encoded = CanonicalCbor.encode(entry.copy(amountMinor = amount)).toHex()
            val marker = "66616d6f756e74" // text(6) "amount"
            val at = encoded.indexOf(marker) + marker.length
            return encoded.substring(at, at + 6)
        }
        assertEquals("17", amountBytes(23).substring(0, 2))     // inline, no prefix
        assertEquals("1818", amountBytes(24).substring(0, 4))   // uint8
        assertEquals("190100", amountBytes(256))                // uint16
    }

    /** The map must carry exactly the ten fields of section 4, in that order. */
    @Test
    fun spendEntryIsATenPairMap() {
        assertEquals("aa", CanonicalCbor.encode(vector()).toHex().substring(0, 2))
    }
}
