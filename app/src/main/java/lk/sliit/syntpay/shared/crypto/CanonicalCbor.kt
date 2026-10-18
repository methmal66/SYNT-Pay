package lk.sliit.syntpay.shared

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Deterministic CBOR, RFC 8949 section 4.2.1. Hand-written on purpose.
 *
 * NEVER ADD A CBOR LIBRARY. SYNTPAY_PROJECT.md section 4 forbids it, and the reason is
 * not stylistic: both apps encode a spend entry and hash it to produce the
 * receipt identifier. If the two encoders differ by a single byte, one payment
 * yields two identifiers and the merchant is credited twice. That failure is
 * silent. A library we do not control is a second encoder we cannot pin.
 *
 * Rules enforced here:
 *   - definite-length encoding only
 *   - smallest possible integer encoding
 *   - map keys in the fixed order of section 4 (which is the RFC 8949 bytewise
 *     lexicographic ordering of the encoded keys; verified, not guessed)
 *   - no indefinite-length strings, no tags, no floats
 *
 * THIS FILE IS SHARED. The wallet copy is canonical. After any change, copy it
 * to the terminal and prove the trees match (section 2).
 */
object CanonicalCbor {

    private const val MT_UINT = 0
    private const val MT_BYTES = 2
    private const val MT_TEXT = 3
    private const val MT_MAP = 5

    /** Major type + argument, always in the shortest form RFC 8949 allows. */
    private fun head(out: ByteArrayOutputStream, majorType: Int, value: Long) {
        require(value >= 0) { "canonical CBOR here encodes unsigned values only" }
        val mt = majorType shl 5
        when {
            value < 24L -> out.write(mt or value.toInt())
            value <= 0xFFL -> {
                out.write(mt or 24)
                out.write(value.toInt() and 0xFF)
            }
            value <= 0xFFFFL -> {
                out.write(mt or 25)
                out.write((value ushr 8).toInt() and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value <= 0xFFFFFFFFL -> {
                out.write(mt or 26)
                for (shift in 24 downTo 0 step 8) out.write((value ushr shift).toInt() and 0xFF)
            }
            else -> {
                out.write(mt or 27)
                for (shift in 56 downTo 0 step 8) out.write((value ushr shift).toInt() and 0xFF)
            }
        }
    }

    private fun uint(out: ByteArrayOutputStream, value: Long) = head(out, MT_UINT, value)

    private fun text(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        head(out, MT_TEXT, bytes.size.toLong())
        out.write(bytes)
    }

    private fun bytes(out: ByteArrayOutputStream, value: ByteArray) {
        head(out, MT_BYTES, value.size.toLong())
        out.write(value)
    }

    /**
     * SpendEntry, section 4. Field order is fixed and must not be reordered:
     * index, txnID, amount, batchID, prevHash, walletID, merchantID,
     * terminalID, voucherRef, terminalTime.
     */
    fun encode(entry: SpendEntry): ByteArray {
        require(entry.txnId.size == TXN_ID_BYTES) { "txnID must be $TXN_ID_BYTES bytes" }
        require(entry.prevHash.size == HASH_BYTES) { "prevHash must be $HASH_BYTES bytes" }
        require(entry.voucherRef.size == HASH_BYTES) { "voucherRef must be $HASH_BYTES bytes" }

        val out = ByteArrayOutputStream()
        head(out, MT_MAP, 10L)
        text(out, "index"); uint(out, entry.index.toLong())
        text(out, "txnID"); bytes(out, entry.txnId)
        text(out, "amount"); uint(out, entry.amountMinor)
        text(out, "batchID"); text(out, entry.batchId)
        text(out, "prevHash"); bytes(out, entry.prevHash)
        text(out, "walletID"); text(out, entry.walletId)
        text(out, "merchantID"); text(out, entry.merchantId)
        text(out, "terminalID"); text(out, entry.terminalId)
        text(out, "voucherRef"); bytes(out, entry.voucherRef)
        text(out, "terminalTime"); uint(out, entry.terminalTime)
        return out.toByteArray()
    }

    /**
     * AllowanceVoucher, section 4. Field order is fixed:
     * batchID, keyRoot, walletID, allowance, expiryBlock, issuedAtBlock,
     * policyVersion.
     */
    fun encode(voucher: AllowanceVoucher): ByteArray {
        require(voucher.keyRoot.size == HASH_BYTES) { "keyRoot must be $HASH_BYTES bytes" }

        val out = ByteArrayOutputStream()
        head(out, MT_MAP, 7L)
        text(out, "batchID"); text(out, voucher.batchId)
        text(out, "keyRoot"); bytes(out, voucher.keyRoot)
        text(out, "walletID"); text(out, voucher.walletId)
        text(out, "allowance"); uint(out, voucher.allowanceMinor)
        text(out, "expiryBlock"); uint(out, voucher.expiryBlock)
        text(out, "issuedAtBlock"); uint(out, voucher.issuedAtBlock)
        text(out, "policyVersion"); uint(out, voucher.policyVersion.toLong())
        return out.toByteArray()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** rid = SHA-256(canonicalEncoding(entry)), section 4. */
    fun receiptId(entry: SpendEntry): ByteArray = sha256(encode(entry))

    /** voucherRef = SHA-256(canonical(voucher)), section 4. */
    fun voucherRef(voucher: AllowanceVoucher): ByteArray = sha256(encode(voucher))

    const val HASH_BYTES = 32
    const val TXN_ID_BYTES = 16

    val ZERO_HASH: ByteArray get() = ByteArray(HASH_BYTES)
}

fun ByteArray.toHex(): String {
    val hex = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        hex[i * 2] = digits[v ushr 4]
        hex[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(hex)
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}
