package lk.sliit.syntpay.wallet.lab

import android.content.Context
import lk.sliit.syntpay.shared.AllowanceVoucher
import lk.sliit.syntpay.shared.CanonicalCbor
import lk.sliit.syntpay.shared.ChainRecord
import lk.sliit.syntpay.shared.KeyBatch
import lk.sliit.syntpay.shared.SpendChain
import lk.sliit.syntpay.shared.SpendEntry
import lk.sliit.syntpay.shared.TerminalVerifier
import lk.sliit.syntpay.wallet.data.WalletStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The Lab, SYNTPAY_PROJECT.md section 7.
 *
 * Each attack edits stored state without touching the burnt keys -- exactly
 * what an attacker on a rooted device can do. The point of every one of them is
 * the same: the chain is editable, the hardware key index is not.
 *
 * The attack corpus below RUNS THE REAL VERIFIER against real signatures. It
 * does not construct the expected refusal and report it back. A corpus that
 * fabricates its own answers proves nothing, and "7 of 7" would be theatre.
 */
class WalletLab {

    // ---- live attacks against the wallet's own stored state ----

    /**
     * Note what these do NOT touch: keysBurnt. Hardware state cannot be rolled
     * back by editing a file, and that asymmetry is the entire mechanism.
     */

    fun deleteLastPayments(state: WalletStore.State, count: Int = 2): Pair<WalletStore.State, String> {
        if (state.records.size < count) {
            return state to "Nothing to delete: the chain holds ${state.records.size} " +
                (if (state.records.size == 1) "entry" else "entries") + "."
        }
        val kept = state.records.dropLast(count)
        val message = "Deleted the last $count entries. The chain now shows ${kept.size}, " +
            "but ${state.keysBurnt} keys are burnt. The terminal will see a gap of " +
            "${state.keysBurnt - kept.size}."
        return state.copy(records = kept) to message
    }

    fun rollBackToEarlierState(state: WalletStore.State): Pair<WalletStore.State, String> {
        if (state.records.isEmpty()) return state to "Nothing to roll back to: the chain is empty."
        val half = state.records.size / 2
        val kept = state.records.take(half)
        return state.copy(records = kept) to
            "Rolled the chain back to $half of ${state.records.size} entries, as a restored " +
            "backup would. ${state.keysBurnt} keys remain burnt."
    }

    fun editStoredAmount(state: WalletStore.State): Pair<WalletStore.State, String> {
        if (state.records.isEmpty()) return state to "Nothing to edit: the chain is empty."
        val target = state.records.lastIndex
        val original = state.records[target]
        val lowered = maxOf(1L, original.entry.amountMinor / 2)
        val edited = original.copy(entry = original.entry.copy(amountMinor = lowered))
        val mutated = state.records.toMutableList().also { it[target] = edited }
        return state.copy(records = mutated) to
            "Lowered entry $target from ${format(original.entry.amountMinor)} to ${format(lowered)}. " +
            "The signature over that entry no longer matches its contents."
    }

    fun corruptPreviousHash(state: WalletStore.State): Pair<WalletStore.State, String> {
        if (state.records.size < 2) return state to "Need at least two entries to break a link."
        val target = state.records.lastIndex
        val original = state.records[target]
        val corrupted = original.entry.prevHash.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val mutated = state.records.toMutableList()
        mutated[target] = original.copy(entry = original.entry.copy(prevHash = corrupted))
        return state.copy(records = mutated) to
            "Flipped a byte in the previous-hash of entry $target. The chain no longer links."
    }

    fun format(minor: Long): String = String.format("%,.2f", minor / 100.0)

    // ---- the attack corpus ----

    data class AttackResult(
        val label: String,
        val expected: TerminalVerifier.RefusalCode?,
        val actualCode: String,
        val detail: String,
        val passed: Boolean,
    )

    data class CorpusReport(val results: List<AttackResult>) {
        val passedCount: Int get() = results.count { it.passed }
        val total: Int get() = results.size
        val summary: String get() = "$passedCount of $total attacks handled correctly"
        val allPassed: Boolean get() = passedCount == total
    }

    /**
     * Build a real signed chain with software EC keys, then mutate it seven ways
     * and run the real verifier over each mutation.
     *
     * Software keys are used here for the same reason the benchmark uses them
     * (section 9): Keystore keys burn on first use and cannot be rebuilt for each
     * variant. The verifier's behaviour is identical either way -- it verifies a
     * signature against a public key and does not care where the private half
     * lived.
     */
    fun runAttackCorpus(): CorpusReport {
        val fixture = buildFixture(entries = 3)
        val results = ArrayList<AttackResult>()

        results += evaluate("Honest wallet (control)", null, fixture.bundle, fixture.proposed, fixture.params)

        results += evaluate(
            "Delete the last 2 payments",
            TerminalVerifier.RefusalCode.REFUSE_GAP,
            fixture.bundle.copy(records = fixture.bundle.records.dropLast(2)),
            fixture.proposed,
            fixture.params,
        )

        results += evaluate(
            "Restore an older backup",
            TerminalVerifier.RefusalCode.REFUSE_GAP,
            fixture.bundle.copy(records = emptyList()),
            fixture.proposed,
            fixture.params,
        )

        // Edit the LAST entry, as the live Lab attack does. Editing an earlier
        // entry changes its hash and so breaks the link to its successor, which
        // step 4 catches before any signature is checked -- a correct refusal,
        // but REFUSE_CHAIN_LINK rather than the signature path we want to cover
        // here. The last entry has no successor, so the chain stays intact and
        // step 5 is what refuses it.
        val lowered = fixture.bundle.records.toMutableList().also { list ->
            val last = list[list.lastIndex]
            list[list.lastIndex] = last.copy(entry = last.entry.copy(amountMinor = 1L))
        }
        results += evaluate(
            "Edit a stored amount to look smaller",
            TerminalVerifier.RefusalCode.REFUSE_ENTRY_SIG,
            fixture.bundle.copy(records = lowered),
            fixture.proposed,
            fixture.params,
        )

        val swapped = fixture.bundle.records.toMutableList().also { list ->
            val a = list[0]; list[0] = list[1]; list[1] = a
        }
        results += evaluate(
            "Swap two entries in the history",
            TerminalVerifier.RefusalCode.REFUSE_CHAIN_LINK,
            fixture.bundle.copy(records = swapped),
            fixture.proposed,
            fixture.params,
        )

        val brokenLink = fixture.bundle.records.toMutableList().also { list ->
            val last = list[list.lastIndex]
            val hash = last.entry.prevHash.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
            list[list.lastIndex] = last.copy(entry = last.entry.copy(prevHash = hash))
        }
        results += evaluate(
            "Corrupt a previous-hash link",
            TerminalVerifier.RefusalCode.REFUSE_CHAIN_LINK,
            fixture.bundle.copy(records = brokenLink),
            fixture.proposed,
            fixture.params,
        )

        val raised = fixture.bundle.voucher.copy(allowanceMinor = 10_000_000L)
        results += evaluate(
            "Edit the voucher to raise the limit",
            TerminalVerifier.RefusalCode.REFUSE_VOUCHER_SIG,
            fixture.bundle.copy(voucher = raised),
            fixture.proposed,
            fixture.params,
        )

        return CorpusReport(results)
    }

    private fun evaluate(
        label: String,
        expected: TerminalVerifier.RefusalCode?,
        bundle: TerminalVerifier.Bundle,
        proposed: SpendEntry,
        params: TerminalVerifier.Params,
    ): AttackResult {
        // Keep the proposed entry consistent with whatever chain is presented.
        val adjusted = proposed.copy(
            index = bundle.records.size,
            prevHash = SpendChain.prevHashFor(bundle.records),
            voucherRef = bundle.voucher.voucherRef(),
        )
        return when (val outcome = TerminalVerifier.verify(bundle, adjusted, params)) {
            is TerminalVerifier.Outcome.Accept -> AttackResult(
                label = label,
                expected = expected,
                actualCode = "ACCEPT",
                detail = "accepted with score ${"%.4f".format(outcome.score)}",
                passed = expected == null,
            )
            is TerminalVerifier.Outcome.Refuse -> AttackResult(
                label = label,
                expected = expected,
                actualCode = outcome.code.name,
                detail = outcome.detail,
                passed = expected == outcome.code,
            )
        }
    }

    // ---- synthetic fixture built with software EC keys ----

    private class Fixture(
        val bundle: TerminalVerifier.Bundle,
        val proposed: SpendEntry,
        val params: TerminalVerifier.Params,
    )

    private fun buildFixture(entries: Int): Fixture {
        val walletId = "wallet-lab01"
        val batchId = "batch-lab01"
        val pairs = (0 until KeyBatch.BATCH_SIZE).map { softwareKeyPair() }
        val publicKeys = pairs.map { it.public.encoded }
        val keyRoot = KeyBatch.merkleRoot(publicKeys)

        val ledger = softwareKeyPair()
        val voucher = AllowanceVoucher(
            batchId = batchId,
            keyRoot = keyRoot,
            walletId = walletId,
            allowanceMinor = 500_000L,
            expiryBlock = 120_000L,
            issuedAtBlock = 94_000L,
            policyVersion = 1,
        )
        val ledgerSignature = sign(ledger, voucher.canonicalEncoding())
        val voucherRef = voucher.voucherRef()

        val records = ArrayList<ChainRecord>()
        for (index in 0 until entries) {
            val entry = SpendEntry(
                index = index,
                txnId = ByteArray(16) { (index * 16 + it).toByte() },
                amountMinor = 2_500L,
                batchId = batchId,
                prevHash = SpendChain.prevHashFor(records),
                walletId = walletId,
                merchantId = "Org1MSP",
                terminalId = "term-lab01",
                voucherRef = voucherRef,
                terminalTime = 1_750_000_000L + index,
            )
            records += ChainRecord(entry, sign(pairs[index], entry.canonicalEncoding()))
        }

        val nextKeyIndex = entries
        val keyMap = HashMap<Int, ByteArray>()
        records.forEach { keyMap[it.entry.index] = publicKeys[it.entry.index] }
        keyMap[nextKeyIndex] = publicKeys[nextKeyIndex]

        val bundle = TerminalVerifier.Bundle(
            voucher = voucher,
            ledgerSignature = ledgerSignature,
            ledgerPublicKeyX509 = ledger.public.encoded,
            records = records,
            nextKeyIndex = nextKeyIndex,
            publicKeysX509 = keyMap,
            merkleProof = KeyBatch.merkleProof(publicKeys, nextKeyIndex),
        )
        val proposed = SpendEntry(
            index = entries,
            txnId = ByteArray(16) { (0xA0 + it).toByte() },
            amountMinor = 5_000L,
            batchId = batchId,
            prevHash = SpendChain.prevHashFor(records),
            walletId = walletId,
            merchantId = "Org1MSP",
            terminalId = "term-lab01",
            voucherRef = voucherRef,
            terminalTime = 1_750_000_100L,
        )
        return Fixture(bundle, proposed, TerminalVerifier.Params(lastKnownBlock = 95_000L))
    }

    private fun softwareKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun sign(pair: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance(KeyBatch.SIGNATURE_ALGORITHM).run {
            initSign(pair.private)
            update(data)
            sign()
        }

    // ---- capability report, section 7 ----

    /**
     * The hardware coverage result. Unlike the earlier draft of this report, the
     * single-use claim is DEMONSTRATED: we sign once, then sign again with the
     * same key and require the second attempt to fail. A report that merely
     * printed "single use enforced" after generating a key would be asserting
     * the very thing it is supposed to be evidence for.
     */
    fun runCapabilityReport(context: Context): String = buildString {
        appendLine("=== SYNT PAY capability report ===")
        appendLine("device          : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("android         : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("StrongBox       : ${KeyBatch.hasStrongBox(context)}")
        appendLine()

        val alias = "syntpay.capability.probe"
        var strongBoxUsed = false
        try {
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) store.deleteEntry(alias)

            strongBoxUsed = KeyBatch.hasStrongBox(context)
            val publicKey = try {
                probeKey(alias, strongBox = strongBoxUsed)
            } catch (e: Exception) {
                strongBoxUsed = false
                probeKey(alias, strongBox = false)
            }
            appendLine("probe key       : EC secp256r1, setMaxUsageCount(1)")
            appendLine("backed by       : ${if (strongBoxUsed) "StrongBox" else "TEE"}")
            appendLine("public key      : ${publicKey.algorithm} ${publicKey.format}")
            appendLine()

            val entry = store.getEntry(alias, null) as java.security.KeyStore.PrivateKeyEntry
            val payload = "syntpay-single-use-probe".toByteArray()

            val first = try {
                Signature.getInstance(KeyBatch.SIGNATURE_ALGORITHM).run {
                    initSign(entry.privateKey); update(payload); sign()
                }
                "OK"
            } catch (e: Exception) {
                "FAILED (${e.javaClass.simpleName})"
            }
            appendLine("first signature : $first")

            val second = try {
                Signature.getInstance(KeyBatch.SIGNATURE_ALGORITHM).run {
                    initSign(entry.privateKey); update(payload); sign()
                }
                "SUCCEEDED"
            } catch (e: Exception) {
                "refused by hardware (${e.javaClass.simpleName})"
            }
            appendLine("second signature: $second")
            appendLine()

            val enforced = first == "OK" && second != "SUCCEEDED"
            appendLine(
                if (enforced) {
                    "Result: PASSED - single use enforced (the second signature was refused)"
                } else {
                    "Result: FAILED - the key signed twice; setMaxUsageCount is NOT enforced here"
                },
            )
            store.deleteEntry(alias)
        } catch (e: Exception) {
            appendLine("Result: ERROR - ${e.javaClass.simpleName}: ${e.message}")
        }

        appendLine()
        appendLine("=== canonical CBOR known-answer test (section 5) ===")
        val katEntry = SpendEntry(
            index = 0,
            txnId = ByteArray(16) { it.toByte() },
            amountMinor = 2_500L,
            batchId = "batch-0001",
            prevHash = ByteArray(32),
            walletId = "wallet-0001",
            merchantId = "Org1MSP",
            terminalId = "term-0001",
            voucherRef = CanonicalCbor.sha256("voucher".toByteArray()),
            terminalTime = 1_750_000_000L,
        )
        val encoded = CanonicalCbor.encode(katEntry)
        val rid = CanonicalCbor.receiptId(katEntry)
        val ridHex = rid.joinToString("") { "%02x".format(it) }
        val expectedRid = "1c17f4837955a32baba235953cc36723de98496c204241d5ce0bcf6c7a34d016"
        appendLine("encoded length  : ${encoded.size} bytes (expected 227)")
        appendLine("receipt id      : $ridHex")
        appendLine("expected        : $expectedRid")
        appendLine(
            if (encoded.size == 227 && ridHex == expectedRid) {
                "Result: PASSED - both apps will agree on receipt identifiers"
            } else {
                "Result: FAILED - the encoder does not match the reference implementation"
            },
        )
    }

    private fun probeKey(alias: String, strongBox: Boolean): java.security.PublicKey {
        val generator = java.security.KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_SIGN,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
            .setMaxUsageCount(1)
            .setIsStrongBoxBacked(strongBox)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }
}
