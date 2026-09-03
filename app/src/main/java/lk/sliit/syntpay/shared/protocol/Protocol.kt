package lk.sliit.syntpay.shared

import java.io.ByteArrayOutputStream

/**
 * The NFC wire protocol, SYNTPAY_PROJECT.md section 6.
 *
 * The wallet emulates a card (HCE); the terminal is the reader. No sockets, no
 * Bluetooth, no network, and neither app requests the INTERNET permission.
 *
 * The payload layout below MUST match byte for byte in both apps. All integers
 * are big endian and all variable-length fields carry a 2-byte length prefix.
 *
 * Note what is deliberately absent: there is no CBOR decoder anywhere. The
 * terminal rebuilds each entry from the header plus the per-entry fields and
 * re-encodes it with its own canonical encoder, so an encoder disagreement
 * surfaces as a refused payment rather than as a duplicate receipt.
 *
 * THIS FILE IS SHARED. The wallet copy is canonical (section 2).
 */
object Protocol {

    /** Seven bytes, category "other". */
    val AID: ByteArray = byteArrayOf(
        0xF0.toByte(), 0x53, 0x4E, 0x54, 0x50, 0x41, 0x59,
    )
    const val AID_HEX = "F0534E54504159"

    const val VERSION: Byte = 1

    // Instruction bytes
    const val CLA_PROPRIETARY: Byte = 0x80.toByte()
    const val INS_SESSION: Byte = 0x10
    const val INS_GET_CHUNK: Byte = 0x20
    const val INS_RESULT: Byte = 0x30

    // Status words
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_WALLET_REFUSED = byteArrayOf(0x6A, 0x80.toByte())
    val SW_CHUNK_OUT_OF_RANGE = byteArrayOf(0x6A, 0x83.toByte())
    val SW_NOT_READY = byteArrayOf(0x6A, 0x88.toByte())
    val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())

    val SELECT_APDU: ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07) + AID + byteArrayOf(0x00)

    /** Chunk size negotiation, section 6. */
    fun chunkSize(maxTransceiveLength: Int, extendedLengthSupported: Boolean): Int =
        if (extendedLengthSupported) minOf(maxTransceiveLength - 8, 4096) else 240

    /** One-byte reasons accompanying SW_WALLET_REFUSED (6A 80). */
    object SelfRefusal {
        const val NOT_PROVISIONED: Byte = 0x01
        const val OFFLINE_DISABLED: Byte = 0x02
        const val INSUFFICIENT_ALLOWANCE: Byte = 0x03
        const val NO_KEYS_REMAINING: Byte = 0x04
        const val SURVIVING_INTENT: Byte = 0x05
        const val VOUCHER_EXPIRED: Byte = 0x06

        fun describe(reason: Byte): String = when (reason) {
            NOT_PROVISIONED -> "the wallet has no allowance voucher yet"
            OFFLINE_DISABLED -> "offline spending is disabled pending resynchronisation"
            INSUFFICIENT_ALLOWANCE -> "the requested amount exceeds the remaining offline allowance"
            NO_KEYS_REMAINING -> "every single-use key in the batch has been burnt"
            SURVIVING_INTENT -> "a write-ahead intent survived a crash; one online resynchronisation is required"
            VOUCHER_EXPIRED -> "the allowance voucher has expired"
            else -> "the wallet refused itself (reason 0x${"%02x".format(reason)})"
        }
    }

    // ---- SESSION request, section 6 ----

    data class SessionRequest(
        val txnId: ByteArray,
        val amountMinor: Long,
        val merchantId: String,
        val terminalId: String,
        val lastKnownBlock: Long,
        val keysCached: Boolean,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    fun encodeSessionRequest(request: SessionRequest): ByteArray {
        require(request.txnId.size == CanonicalCbor.TXN_ID_BYTES) { "txnId must be 16 bytes" }
        val out = ByteArrayOutputStream()
        out.write(request.txnId)
        writeLong(out, request.amountMinor)
        writeByteString(out, request.merchantId)
        writeByteString(out, request.terminalId)
        writeLong(out, request.lastKnownBlock)
        out.write(if (request.keysCached) 1 else 0)
        return out.toByteArray()
    }

    fun decodeSessionRequest(data: ByteArray): SessionRequest {
        val r = Reader(data)
        val txnId = r.bytes(CanonicalCbor.TXN_ID_BYTES)
        val amount = r.long()
        val merchant = r.byteString()
        val terminal = r.byteString()
        val lastKnownBlock = r.long()
        val keysCached = r.byte().toInt() == 1
        return SessionRequest(txnId, amount, merchant, terminal, lastKnownBlock, keysCached)
    }

    /** SESSION response: chainLength(2) nextKeyIndex(2) remaining(8) totalPayloadLen(4). */
    data class SessionResponse(
        val chainLength: Int,
        val nextKeyIndex: Int,
        val remainingMinor: Long,
        val totalPayloadLength: Int,
    )

    fun encodeSessionResponse(response: SessionResponse): ByteArray {
        val out = ByteArrayOutputStream()
        writeShort(out, response.chainLength)
        writeShort(out, response.nextKeyIndex)
        writeLong(out, response.remainingMinor)
        writeInt(out, response.totalPayloadLength)
        return out.toByteArray()
    }

    fun decodeSessionResponse(data: ByteArray): SessionResponse {
        val r = Reader(data)
        return SessionResponse(r.short(), r.short(), r.long(), r.int())
    }

    // ---- RESULT request, section 6 ----

    data class ResultRequest(
        val accepted: Boolean,
        val code: String,
        val detail: String,
        val terminalSignature: ByteArray,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    fun encodeResultRequest(result: ResultRequest): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(if (result.accepted) 1 else 0)
        writeShortString(out, result.code)
        writeShortString(out, result.detail)
        writeShortBytes(out, result.terminalSignature)
        return out.toByteArray()
    }

    fun decodeResultRequest(data: ByteArray): ResultRequest {
        val r = Reader(data)
        val accepted = r.byte().toInt() == 1
        return ResultRequest(accepted, r.shortString(), r.shortString(), r.shortBytes())
    }

    // ---- APDU helpers ----

    fun getChunkApdu(chunkIndex: Int): ByteArray = byteArrayOf(
        CLA_PROPRIETARY, INS_GET_CHUNK,
        ((chunkIndex shr 8) and 0xFF).toByte(),
        (chunkIndex and 0xFF).toByte(),
        0x00,
    )

    fun commandApdu(ins: Byte, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CLA_PROPRIETARY.toInt())
        out.write(ins.toInt())
        out.write(0)
        out.write(0)
        out.write(payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    fun isSuccess(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()

    fun statusWord(response: ByteArray): Int =
        if (response.size < 2) -1
        else ((response[response.size - 2].toInt() and 0xFF) shl 8) or
            (response[response.size - 1].toInt() and 0xFF)

    fun body(response: ByteArray): ByteArray =
        if (response.size <= 2) ByteArray(0) else response.copyOfRange(0, response.size - 2)

    fun selectsOurAid(apdu: ByteArray): Boolean {
        if (apdu.size < 5 + AID.size) return false
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte()) return false
        val length = apdu[4].toInt() and 0xFF
        if (length != AID.size) return false
        for (i in AID.indices) if (apdu[5 + i] != AID[i]) return false
        return true
    }


    // ---- the chunked payload, section 6 ----

    /**
     * The decoded bundle payload.
     *
     * walletId, batchId and voucherRef are NOT repeated per entry; they are
     * carried once in the header and reattached to every entry here. That
     * deduplication roughly halves the transfer.
     *
     * DEVIATION FROM SECTION 6, STATED OPENLY: section 6's layout lists HEADER,
     * PER ENTRY and KEYS but no Merkle proof, while section 3 step 6 requires the
     * terminal to verify attestation for the current key against keyRoot. Since
     * only the keys present in the chain plus the next unused one are sent, the
     * terminal cannot recompute that proof on its own. A PROOF section is
     * therefore appended after KEYS. It is the smallest addition that lets step
     * 6 actually run.
     */
    data class Payload(
        val voucher: AllowanceVoucher,
        val ledgerSignature: ByteArray,
        val ledgerPublicKeyX509: ByteArray,
        val nextKeyIndex: Int,
        val records: List<ChainRecord>,
        val keysX509: Map<Int, ByteArray>,
        val merkleProof: List<ByteArray>,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Build the byte array the wallet serves in chunks. */
    fun encodePayload(payload: Payload, includeKeys: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val v = payload.voucher

        // HEADER
        writeShortString(out, v.batchId)
        writeShortString(out, v.walletId)
        writeLong(out, v.allowanceMinor)
        writeLong(out, v.expiryBlock)
        writeLong(out, v.issuedAtBlock)
        writeShort(out, v.policyVersion)
        out.write(v.keyRoot)
        writeShortBytes(out, payload.ledgerSignature)
        writeShortBytes(out, payload.ledgerPublicKeyX509)
        writeShort(out, payload.nextKeyIndex)
        writeShort(out, payload.records.size)

        // PER ENTRY
        for (record in payload.records) {
            val e = record.entry
            writeShort(out, e.index)
            out.write(e.txnId)
            writeLong(out, e.amountMinor)
            out.write(e.prevHash)
            writeShortString(out, e.merchantId)
            writeShortString(out, e.terminalId)
            writeLong(out, e.terminalTime)
            writeShortBytes(out, record.walletSignature)
        }

        // KEYS (omitted entirely when the terminal already has this batch)
        if (includeKeys) {
            val entries = payload.keysX509.entries.sortedBy { it.key }
            writeShort(out, entries.size)
            for ((index, key) in entries) {
                writeShort(out, index)
                writeShortBytes(out, key)
            }
        } else {
            writeShort(out, 0)
        }

        // PROOF (see the note on Payload)
        writeShort(out, payload.merkleProof.size)
        for (node in payload.merkleProof) out.write(node)

        return out.toByteArray()
    }

    /**
     * Rebuild the bundle from the wire. Note that each entry is reconstructed
     * from the header plus its own fields and then re-encoded with OUR canonical
     * encoder before its signature is checked, so an encoder disagreement
     * surfaces as a refused payment and never as a duplicate receipt.
     */
    fun decodePayload(data: ByteArray): Payload {
        val r = Reader(data)

        val batchId = r.shortString()
        val walletId = r.shortString()
        val allowance = r.long()
        val expiryBlock = r.long()
        val issuedAtBlock = r.long()
        val policyVersion = r.short()
        val keyRoot = r.bytes(CanonicalCbor.HASH_BYTES)
        val ledgerSignature = r.shortBytes()
        val ledgerPublicKey = r.shortBytes()
        val nextKeyIndex = r.short()
        val entryCount = r.short()

        val voucher = AllowanceVoucher(
            batchId = batchId,
            keyRoot = keyRoot,
            walletId = walletId,
            allowanceMinor = allowance,
            expiryBlock = expiryBlock,
            issuedAtBlock = issuedAtBlock,
            policyVersion = policyVersion,
        )
        val voucherRef = voucher.voucherRef()

        val records = ArrayList<ChainRecord>(entryCount)
        repeat(entryCount) {
            val index = r.short()
            val txnId = r.bytes(CanonicalCbor.TXN_ID_BYTES)
            val amount = r.long()
            val prevHash = r.bytes(CanonicalCbor.HASH_BYTES)
            val merchantId = r.shortString()
            val terminalId = r.shortString()
            val terminalTime = r.long()
            val walletSignature = r.shortBytes()
            records += ChainRecord(
                entry = SpendEntry(
                    index = index,
                    txnId = txnId,
                    amountMinor = amount,
                    batchId = batchId,
                    prevHash = prevHash,
                    walletId = walletId,
                    merchantId = merchantId,
                    terminalId = terminalId,
                    voucherRef = voucherRef,
                    terminalTime = terminalTime,
                ),
                walletSignature = walletSignature,
            )
        }

        val keyCount = r.short()
        val keys = HashMap<Int, ByteArray>(keyCount)
        repeat(keyCount) {
            val index = r.short()
            keys[index] = r.shortBytes()
        }

        val proofCount = r.short()
        val proof = ArrayList<ByteArray>(proofCount)
        repeat(proofCount) { proof += r.bytes(CanonicalCbor.HASH_BYTES) }

        return Payload(
            voucher = voucher,
            ledgerSignature = ledgerSignature,
            ledgerPublicKeyX509 = ledgerPublicKey,
            nextKeyIndex = nextKeyIndex,
            records = records,
            keysX509 = keys,
            merkleProof = proof,
        )
    }

    /** How many chunks a payload of [size] needs at [chunkSize]. */
    fun chunkCount(size: Int, chunkSize: Int): Int =
        if (chunkSize <= 0) 0 else (size + chunkSize - 1) / chunkSize

    fun chunkAt(payload: ByteArray, index: Int, chunkSize: Int): ByteArray? {
        val start = index * chunkSize
        if (start >= payload.size || index < 0) return null
        val end = minOf(start + chunkSize, payload.size)
        return payload.copyOfRange(start, end)
    }

    // ---- primitive writers and the matching reader ----

    fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun writeInt(out: ByteArrayOutputStream, value: Int) {
        for (shift in 24 downTo 0 step 8) out.write((value shr shift) and 0xFF)
    }

    fun writeLong(out: ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }

    /** 1-byte length prefix, used by the SESSION request only. */
    fun writeByteString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFF) { "value too long for a 1-byte length prefix" }
        out.write(bytes.size)
        out.write(bytes)
    }

    /** 2-byte length prefix, used throughout the payload. */
    fun writeShortString(out: ByteArrayOutputStream, value: String) =
        writeShortBytes(out, value.toByteArray(Charsets.UTF_8))

    fun writeShortBytes(out: ByteArrayOutputStream, value: ByteArray) {
        require(value.size <= 0xFFFF) { "value too long for a 2-byte length prefix" }
        writeShort(out, value.size)
        out.write(value)
    }

    class Reader(private val data: ByteArray, private var offset: Int = 0) {
        val remaining: Int get() = data.size - offset

        private fun need(count: Int) {
            if (offset + count > data.size) {
                throw IllegalArgumentException(
                    "payload truncated: needed $count more bytes at offset $offset of ${data.size}",
                )
            }
        }

        fun byte(): Byte { need(1); return data[offset++] }

        fun bytes(count: Int): ByteArray {
            need(count)
            val slice = data.copyOfRange(offset, offset + count)
            offset += count
            return slice
        }

        fun short(): Int {
            need(2)
            val v = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            return v
        }

        fun int(): Int {
            need(4)
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (data[offset + i].toInt() and 0xFF)
            offset += 4
            return v
        }

        fun long(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
            offset += 8
            return v
        }

        fun byteString(): String {
            val length = byte().toInt() and 0xFF
            return String(bytes(length), Charsets.UTF_8)
        }

        fun shortBytes(): ByteArray = bytes(short())

        fun shortString(): String = String(shortBytes(), Charsets.UTF_8)
    }
}
