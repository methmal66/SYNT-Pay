package lk.sliit.syntpay.shared

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.math.min

/**
 * Component A: the nine-step offline acceptance verifier, SYNTPAY_PROJECT.md section 3.
 *
 * IT FAILS CLOSED. Every check that does not demonstrably pass refuses. There is
 * no path through this object that accepts on missing evidence.
 *
 * Steps 1-3 (credential) run before steps 4-6 (cryptography) by design, so an
 * invalid wallet is refused before any expensive work is done.
 *
 * Refusal codes and detail strings are displayed VERBATIM by both apps and must
 * never be softened.
 *
 * THIS FILE IS SHARED. The wallet copy is canonical (section 2).
 */
object TerminalVerifier {

    const val DEFAULT_THRESHOLD = 0.75
    const val DEFAULT_DEADLINE_MS = 500L
    private const val DAY_SECONDS = 86_400.0

    enum class RefusalCode {
        REFUSE_VOUCHER_SIG,
        REFUSE_EXPIRED,
        REFUSE_REVOKED,
        REFUSE_CHAIN_LINK,
        REFUSE_ENTRY_SIG,
        REFUSE_ATTESTATION,
        REFUSE_GAP,
        REFUSE_ALLOWANCE,
        REFUSE_POLICY,
    }

    /** Stage names are the ones reported on the latency panel (section 8). */
    object Stage {
        const val TRANSPORT = "S0_transport"
        const val CREDENTIAL = "S1_credential"
        const val CHAIN = "S2_chain"
        const val SIGNATURES = "S3_signatures"
        const val GAPCHECK = "S4_gapcheck"
        const val POLICY = "S5_policy"
    }

    data class Bundle(
        val voucher: AllowanceVoucher,
        val ledgerSignature: ByteArray,
        val ledgerPublicKeyX509: ByteArray,
        val records: List<ChainRecord>,
        /** The index of the next unused hardware key, as presented by the wallet. */
        val nextKeyIndex: Int,
        /** index -> X.509 public key, for the indices present plus the next unused one. */
        val publicKeysX509: Map<Int, ByteArray>,
        val merkleProof: List<ByteArray> = emptyList(),
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Params(
        val lastKnownBlock: Long = 95_000L,
        val threshold: Double = DEFAULT_THRESHOLD,
        val revocationList: Set<String> = emptySet(),
        val syncAgeSeconds: Long = 0L,
        val revListAgeSeconds: Long = 0L,
        /** Pinned on first use; a different key later is refused (section 8). */
        val pinnedLedgerKeyX509: ByteArray? = null,
    )

    sealed interface Outcome {
        val stageNanos: Map<String, Long>

        data class Accept(
            val receiptId: ByteArray,
            val chainIndex: Int,
            val remainingAfter: Long,
            val score: Double,
            val entryCanonicalEncoding: ByteArray,
            override val stageNanos: Map<String, Long>,
        ) : Outcome

        data class Refuse(
            val code: RefusalCode,
            /** Displayed verbatim. Never soften this string. */
            val detail: String,
            override val stageNanos: Map<String, Long>,
        ) : Outcome
    }

    /**
     * Verify a presented bundle against a proposed payment.
     *
     * [proposed] is the entry the terminal wants to append. The terminal builds
     * it from its own fields and re-encodes it with its own encoder, so an
     * encoder disagreement surfaces as a refused payment rather than a duplicate
     * receipt (section 6).
     */
    fun verify(bundle: Bundle, proposed: SpendEntry, params: Params = Params()): Outcome {
        val stages = LinkedHashMap<String, Long>()

        // ---- Steps 1-3: credential checks, cheap, run first by design ----
        val credentialStart = System.nanoTime()

        // 1. the ledger signature over the voucher
        val voucherBytes = bundle.voucher.canonicalEncoding()
        val pinned = params.pinnedLedgerKeyX509
        if (pinned != null && !pinned.contentEquals(bundle.ledgerPublicKeyX509)) {
            stages[Stage.CREDENTIAL] = System.nanoTime() - credentialStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_VOUCHER_SIG,
                "ledger key does not match the pinned key",
                stages,
            )
        }
        if (!verifySignature(bundle.ledgerPublicKeyX509, voucherBytes, bundle.ledgerSignature)) {
            stages[Stage.CREDENTIAL] = System.nanoTime() - credentialStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_VOUCHER_SIG,
                "ledger signature over the allowance voucher did not verify",
                stages,
            )
        }

        // 2. expiry against the last known block height
        if (params.lastKnownBlock > bundle.voucher.expiryBlock) {
            stages[Stage.CREDENTIAL] = System.nanoTime() - credentialStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_EXPIRED,
                "voucher expired at block ${bundle.voucher.expiryBlock}, last known block is ${params.lastKnownBlock}",
                stages,
            )
        }

        // 3. local revocation list
        if (params.revocationList.contains(bundle.voucher.walletId)) {
            stages[Stage.CREDENTIAL] = System.nanoTime() - credentialStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_REVOKED,
                "wallet ${bundle.voucher.walletId} is on the local revocation list",
                stages,
            )
        }
        stages[Stage.CREDENTIAL] = System.nanoTime() - credentialStart

        // ---- Step 4: chain integrity ----
        val chainStart = System.nanoTime()
        val broken = SpendChain.firstBrokenLink(bundle.records)
        if (broken >= 0) {
            stages[Stage.CHAIN] = System.nanoTime() - chainStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_CHAIN_LINK,
                "chain link broken at entry $broken: prevHash does not match the preceding entry",
                stages,
            )
        }
        // Every entry must belong to this voucher and wallet.
        val expectedVoucherRef = bundle.voucher.voucherRef()
        for (record in bundle.records) {
            if (!record.entry.voucherRef.contentEquals(expectedVoucherRef)) {
                stages[Stage.CHAIN] = System.nanoTime() - chainStart
                return Outcome.Refuse(
                    RefusalCode.REFUSE_CHAIN_LINK,
                    "entry ${record.entry.index} references a different allowance voucher",
                    stages,
                )
            }
            if (record.entry.walletId != bundle.voucher.walletId ||
                record.entry.batchId != bundle.voucher.batchId
            ) {
                stages[Stage.CHAIN] = System.nanoTime() - chainStart
                return Outcome.Refuse(
                    RefusalCode.REFUSE_CHAIN_LINK,
                    "entry ${record.entry.index} does not belong to this wallet or key batch",
                    stages,
                )
            }
        }
        stages[Stage.CHAIN] = System.nanoTime() - chainStart

        // ---- Steps 5-6: signatures and attestation, the expensive part ----
        val signatureStart = System.nanoTime()
        for (record in bundle.records) {
            val keyBytes = bundle.publicKeysX509[record.entry.index]
            if (keyBytes == null) {
                stages[Stage.SIGNATURES] = System.nanoTime() - signatureStart
                return Outcome.Refuse(
                    RefusalCode.REFUSE_ENTRY_SIG,
                    "no batch public key presented for entry ${record.entry.index}",
                    stages,
                )
            }
            // Re-encode with OUR encoder, then verify against that.
            val reEncoded = record.entry.canonicalEncoding()
            if (!verifySignature(keyBytes, reEncoded, record.walletSignature)) {
                stages[Stage.SIGNATURES] = System.nanoTime() - signatureStart
                return Outcome.Refuse(
                    RefusalCode.REFUSE_ENTRY_SIG,
                    "signature on entry ${record.entry.index} did not verify against the batch key at that index",
                    stages,
                )
            }
        }

        // 6. attestation for the current key, by Merkle proof against keyRoot
        val currentKey = bundle.publicKeysX509[bundle.nextKeyIndex]
        if (currentKey == null) {
            stages[Stage.SIGNATURES] = System.nanoTime() - signatureStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_ATTESTATION,
                "no public key presented for the next unused key index ${bundle.nextKeyIndex}",
                stages,
            )
        }
        if (!KeyBatch.verifyMerkleProof(
                currentKey,
                bundle.nextKeyIndex,
                bundle.merkleProof,
                bundle.voucher.keyRoot,
            )
        ) {
            stages[Stage.SIGNATURES] = System.nanoTime() - signatureStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_ATTESTATION,
                "Merkle proof for key index ${bundle.nextKeyIndex} does not reproduce the keyRoot in the voucher",
                stages,
            )
        }
        stages[Stage.SIGNATURES] = System.nanoTime() - signatureStart

        // ---- Step 7: THE GAP CHECK. This is the decisive one. ----
        val gapStart = System.nanoTime()
        val entriesPresented = bundle.records.size
        if (bundle.nextKeyIndex != entriesPresented) {
            val concealed = bundle.nextKeyIndex - entriesPresented
            stages[Stage.GAPCHECK] = System.nanoTime() - gapStart
            val detail = if (concealed > 0) {
                "Key index ${bundle.nextKeyIndex} but $entriesPresented " +
                    (if (entriesPresented == 1) "entry" else "entries") +
                    " presented — $concealed ${if (concealed == 1) "payment" else "payments"} concealed."
            } else {
                "Key index ${bundle.nextKeyIndex} but $entriesPresented entries presented — chain longer than the burnt key count."
            }
            return Outcome.Refuse(RefusalCode.REFUSE_GAP, detail, stages)
        }
        stages[Stage.GAPCHECK] = System.nanoTime() - gapStart

        // ---- Steps 8-9: allowance and policy ----
        val policyStart = System.nanoTime()
        val allowance = bundle.voucher.allowanceMinor
        val spent = SpendChain.spent(bundle.records)
        val remainingBefore = allowance - spent
        val amount = proposed.amountMinor
        if (amount > remainingBefore) {
            stages[Stage.POLICY] = System.nanoTime() - policyStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_ALLOWANCE,
                "amount $amount exceeds the remaining offline allowance $remainingBefore",
                stages,
            )
        }

        val score = acceptanceScore(
            amountMinor = amount,
            allowanceMinor = allowance,
            remainingBefore = remainingBefore,
            issuedAtBlock = bundle.voucher.issuedAtBlock,
            expiryBlock = bundle.voucher.expiryBlock,
            lastKnownBlock = params.lastKnownBlock,
            syncAgeSeconds = params.syncAgeSeconds,
            revListAgeSeconds = params.revListAgeSeconds,
        )
        if (score >= params.threshold) {
            stages[Stage.POLICY] = System.nanoTime() - policyStart
            return Outcome.Refuse(
                RefusalCode.REFUSE_POLICY,
                "acceptance score ${"%.4f".format(score)} is at or above the refusal threshold ${"%.2f".format(params.threshold)}",
                stages,
            )
        }
        stages[Stage.POLICY] = System.nanoTime() - policyStart

        val encoding = proposed.canonicalEncoding()
        return Outcome.Accept(
            receiptId = CanonicalCbor.sha256(encoding),
            chainIndex = proposed.index,
            remainingAfter = remainingBefore - amount,
            score = score,
            entryCanonicalEncoding = encoding,
            stageNanos = stages,
        )
    }

    /**
     * The acceptance policy of section 3, transcribed exactly:
     *
     *   score = 0.4*ratio + 0.2*voucherAge
     *         + 0.2*min(1, syncAgeSeconds/86400)
     *         + 0.2*min(1, revListAgeSeconds/86400)
     *         - 0.1*headroom
     *
     * Accept when score < threshold. Hard rules above override this entirely.
     */
    fun acceptanceScore(
        amountMinor: Long,
        allowanceMinor: Long,
        remainingBefore: Long,
        issuedAtBlock: Long,
        expiryBlock: Long,
        lastKnownBlock: Long,
        syncAgeSeconds: Long,
        revListAgeSeconds: Long,
    ): Double {
        if (allowanceMinor <= 0L) return 1.0
        val l = allowanceMinor.toDouble()
        val ratio = amountMinor / l
        val headroom = (remainingBefore - amountMinor) / l
        val span = (expiryBlock - issuedAtBlock).toDouble()
        val voucherAge = if (span <= 0.0) 1.0 else (lastKnownBlock - issuedAtBlock) / span
        return 0.4 * ratio +
            0.2 * voucherAge +
            0.2 * min(1.0, syncAgeSeconds / DAY_SECONDS) +
            0.2 * min(1.0, revListAgeSeconds / DAY_SECONDS) -
            0.1 * headroom
    }

    fun verifySignature(publicKeyX509: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyX509))
            Signature.getInstance(KeyBatch.SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        } catch (e: Exception) {
            // Fails closed: any malformed key or signature is a refusal, not a pass.
            false
        }
}
