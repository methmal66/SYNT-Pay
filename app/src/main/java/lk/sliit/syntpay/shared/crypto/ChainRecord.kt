package lk.sliit.syntpay.shared

/**
 * The hash-linked spend chain, SYNTPAY_PROJECT.md section 3.
 *
 * THE CENTRAL INVARIANT: the balance is DERIVED, every time, as
 * allowance - sum(chain entry amounts). There is no balance field stored
 * anywhere in this project. That absence is the security property: there is no
 * number on the phone for an attacker to edit. If you ever find yourself adding
 * a `balance` field, you have broken the design.
 *
 * THIS FILE IS SHARED. The wallet copy is canonical (section 2).
 */

/** One spend entry. Field names and order mirror section 4 exactly. */
data class SpendEntry(
    val index: Int,
    val txnId: ByteArray,
    val amountMinor: Long,
    val batchId: String,
    val prevHash: ByteArray,
    val walletId: String,
    val merchantId: String,
    val terminalId: String,
    val voucherRef: ByteArray,
    /** Advisory only. Supplied by the terminal and never trusted (section 4). */
    val terminalTime: Long,
) {
    fun canonicalEncoding(): ByteArray = CanonicalCbor.encode(this)
    fun receiptId(): ByteArray = CanonicalCbor.receiptId(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpendEntry) return false
        return index == other.index &&
            txnId.contentEquals(other.txnId) &&
            amountMinor == other.amountMinor &&
            batchId == other.batchId &&
            prevHash.contentEquals(other.prevHash) &&
            walletId == other.walletId &&
            merchantId == other.merchantId &&
            terminalId == other.terminalId &&
            voucherRef.contentEquals(other.voucherRef) &&
            terminalTime == other.terminalTime
    }

    override fun hashCode(): Int {
        var r = index
        r = 31 * r + txnId.contentHashCode()
        r = 31 * r + amountMinor.hashCode()
        r = 31 * r + batchId.hashCode()
        r = 31 * r + prevHash.contentHashCode()
        r = 31 * r + walletId.hashCode()
        r = 31 * r + merchantId.hashCode()
        r = 31 * r + terminalId.hashCode()
        r = 31 * r + voucherRef.contentHashCode()
        r = 31 * r + terminalTime.hashCode()
        return r
    }
}

/** The ledger-signed allowance voucher that locks L in escrow (section 3). */
data class AllowanceVoucher(
    val batchId: String,
    val keyRoot: ByteArray,
    val walletId: String,
    val allowanceMinor: Long,
    val expiryBlock: Long,
    val issuedAtBlock: Long,
    val policyVersion: Int,
) {
    fun canonicalEncoding(): ByteArray = CanonicalCbor.encode(this)
    fun voucherRef(): ByteArray = CanonicalCbor.voucherRef(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AllowanceVoucher) return false
        return batchId == other.batchId &&
            keyRoot.contentEquals(other.keyRoot) &&
            walletId == other.walletId &&
            allowanceMinor == other.allowanceMinor &&
            expiryBlock == other.expiryBlock &&
            issuedAtBlock == other.issuedAtBlock &&
            policyVersion == other.policyVersion
    }

    override fun hashCode(): Int {
        var r = batchId.hashCode()
        r = 31 * r + keyRoot.contentHashCode()
        r = 31 * r + walletId.hashCode()
        r = 31 * r + allowanceMinor.hashCode()
        r = 31 * r + expiryBlock.hashCode()
        r = 31 * r + issuedAtBlock.hashCode()
        r = 31 * r + policyVersion
        return r
    }
}

/** A persisted chain entry: the entry plus the signatures over it. */
data class ChainRecord(
    val entry: SpendEntry,
    /** Signature by the single-use batch key at index [SpendEntry.index]. */
    val walletSignature: ByteArray,
    /** Present once a terminal has accepted and co-signed the receipt. */
    val terminalSignature: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainRecord) return false
        if (entry != other.entry) return false
        if (!walletSignature.contentEquals(other.walletSignature)) return false
        val a = terminalSignature
        val b = other.terminalSignature
        return if (a == null) b == null else b != null && a.contentEquals(b)
    }

    override fun hashCode(): Int {
        var r = entry.hashCode()
        r = 31 * r + walletSignature.contentHashCode()
        r = 31 * r + (terminalSignature?.contentHashCode() ?: 0)
        return r
    }
}

object SpendChain {

    /**
     * prevHash for position [index]: SHA-256 of the canonical encoding of the
     * preceding entry, or 32 zero bytes at position 0 (section 4).
     */
    fun prevHashFor(records: List<ChainRecord>): ByteArray =
        records.lastOrNull()?.let { CanonicalCbor.sha256(it.entry.canonicalEncoding()) }
            ?: CanonicalCbor.ZERO_HASH

    /** Sum of the amounts actually present in the chain. */
    fun spent(records: List<ChainRecord>): Long = records.sumOf { it.entry.amountMinor }

    /**
     * The derived balance. There is no stored alternative to this function, and
     * there must never be one (section 3).
     */
    fun remaining(voucher: AllowanceVoucher, records: List<ChainRecord>): Long =
        voucher.allowanceMinor - spent(records)

    /**
     * Verify that every prevHash links to its predecessor. Returns the index of
     * the first broken link, or -1 when the chain is intact.
     */
    fun firstBrokenLink(records: List<ChainRecord>): Int {
        var expected = CanonicalCbor.ZERO_HASH
        for ((position, record) in records.withIndex()) {
            if (!record.entry.prevHash.contentEquals(expected)) return position
            if (record.entry.index != position) return position
            expected = CanonicalCbor.sha256(record.entry.canonicalEncoding())
        }
        return -1
    }
}
