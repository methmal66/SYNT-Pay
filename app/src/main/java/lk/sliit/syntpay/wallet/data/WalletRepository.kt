package lk.sliit.syntpay.wallet.data

import android.content.Context
import lk.sliit.syntpay.shared.CanonicalCbor
import lk.sliit.syntpay.shared.ChainRecord
import lk.sliit.syntpay.shared.KeyBatch
import lk.sliit.syntpay.shared.Protocol
import lk.sliit.syntpay.shared.SpendChain
import lk.sliit.syntpay.shared.SpendEntry
import lk.sliit.syntpay.shared.TerminalVerifier
import lk.sliit.syntpay.shared.toHex
import lk.sliit.syntpay.wallet.ledger.LocalLedgerStub
import java.security.SecureRandom

/**
 * The mechanism, SYNTPAY_PROJECT.md section 3.
 *
 * Nothing here returns a stored balance, because none exists. Every figure the
 * UI shows is recomputed from the voucher and the chain at the moment it is
 * asked for.
 */
class WalletRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = WalletStore(appContext)
    private val ledger = LocalLedgerStub(appContext)
    private val random = SecureRandom()

    fun load(): WalletStore.State = store.load()

    /** allowance - sum(chain). The only definition of the balance there is. */
    fun remaining(state: WalletStore.State): Long =
        state.voucher?.let { SpendChain.remaining(it, state.records) } ?: 0L

    fun spent(state: WalletStore.State): Long = SpendChain.spent(state.records)

    fun keysRemaining(state: WalletStore.State): Int = KeyBatch.BATCH_SIZE - state.keysBurnt

    /**
     * On launch, a surviving write-ahead intent means a signing attempt did not
     * complete. The honest wallet has developed a real gap, so offline spending
     * is disabled until one online resynchronisation (section 3).
     */
    fun recoverOnLaunch(): WalletStore.State {
        val state = store.load()
        if (state.pendingIntent == null) return state
        val disabled = state.copy(
            offlineDisabledReason = "A payment was interrupted before it completed. " +
                "The spend chain may have a gap, so offline spending is disabled " +
                "until this wallet resynchronises once online.",
            pendingIntent = null,
            // The key was very likely burnt; assume the worst rather than the best.
            keysBurnt = minOf(KeyBatch.BATCH_SIZE, maxOf(state.keysBurnt, state.pendingIntent.keyIndex + 1)),
        )
        store.save(disabled)
        return disabled
    }

    /** Generate the key batch, have the (stubbed) ledger issue a voucher, persist. */
    fun provision(): WalletStore.State {
        val walletId = "wallet-" + randomHex(4)
        val batchId = "batch-" + randomHex(4)

        val generation = KeyBatch.generate(appContext, batchId)
        val signed = ledger.issueVoucher(walletId, batchId, generation.keyRoot)

        val state = WalletStore.State(
            walletId = walletId,
            batchId = batchId,
            voucher = signed.voucher,
            ledgerSignature = signed.ledgerSignature,
            ledgerPublicKeyX509 = signed.ledgerPublicKeyX509,
            publicKeysX509 = generation.publicKeysX509,
            records = emptyList(),
            keysBurnt = 0,
            strongBoxBacked = generation.strongBoxBacked,
            offlineDisabledReason = null,
            pendingIntent = null,
        )
        store.save(state)
        store.backup(state)
        return state
    }

    fun reset(): WalletStore.State {
        val state = store.load()
        if (state.batchId.isNotEmpty()) KeyBatch.deleteBatch(state.batchId)
        store.wipe()
        return store.empty()
    }

    /** A fresh transaction id. The terminal supplies this in a real sale. */
    fun newTxnId(): ByteArray = ByteArray(CanonicalCbor.TXN_ID_BYTES).also { random.nextBytes(it) }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.toHex()

    /** Why this wallet would refuse itself before presenting anything (section 6). */
    fun selfRefusalReason(state: WalletStore.State, amountMinor: Long): Byte? = when {
        !state.isProvisioned -> Protocol.SelfRefusal.NOT_PROVISIONED
        state.pendingIntent != null -> Protocol.SelfRefusal.SURVIVING_INTENT
        state.offlineDisabledReason != null -> Protocol.SelfRefusal.OFFLINE_DISABLED
        state.keysBurnt >= KeyBatch.BATCH_SIZE -> Protocol.SelfRefusal.NO_KEYS_REMAINING
        amountMinor > remaining(state) -> Protocol.SelfRefusal.INSUFFICIENT_ALLOWANCE
        else -> null
    }

    /**
     * Sign a payment into the chain. Called during SESSION so the signing cost
     * lands in one measurable place (section 6).
     *
     * The entry is signed by the key at index == the chain position. On an
     * honest wallet that is also the next unused key. On a wallet whose history
     * has been edited it is a key that has already been burnt, so the hardware
     * refuses and [SignOutcome.KeyAlreadyBurnt] comes back. We then present the
     * chain exactly as it stands and let the terminal's gap check speak. This is
     * the mechanism working, not an error to paper over.
     */
    fun signPayment(
        state: WalletStore.State,
        amountMinor: Long,
        merchantId: String,
        terminalId: String,
        txnId: ByteArray,
        terminalTime: Long,
    ): SignOutcome {
        val voucher = state.voucher ?: return SignOutcome.NotProvisioned
        val entryIndex = state.records.size
        if (entryIndex >= KeyBatch.BATCH_SIZE) return SignOutcome.NoKeysRemaining

        val entry = SpendEntry(
            index = entryIndex,
            txnId = txnId,
            amountMinor = amountMinor,
            batchId = state.batchId,
            prevHash = SpendChain.prevHashFor(state.records),
            walletId = state.walletId,
            merchantId = merchantId,
            terminalId = terminalId,
            voucherRef = voucher.voucherRef(),
            terminalTime = terminalTime,
        )

        // Write-ahead intent BEFORE signing (section 3).
        val withIntent = store.recordIntent(
            state,
            WalletStore.PendingIntent(
                keyIndex = entryIndex,
                amountMinor = amountMinor,
                merchantId = merchantId,
                terminalId = terminalId,
                txnIdHex = txnId.toHex(),
            ),
        )

        val signature = try {
            KeyBatch.sign(state.batchId, entryIndex, entry.canonicalEncoding())
        } catch (e: Exception) {
            // The key at this index is already burnt: the history was edited.
            // Clear the intent (nothing was appended) and report it honestly.
            store.clearIntent(withIntent)
            return SignOutcome.KeyAlreadyBurnt(entryIndex, state.keysBurnt)
        }

        val record = ChainRecord(entry = entry, walletSignature = signature)
        val committed = withIntent.copy(
            records = withIntent.records + record,
            keysBurnt = maxOf(withIntent.keysBurnt, entryIndex + 1),
        )
        store.save(committed)
        // Intent cleared AFTER the entry is persisted (section 3).
        val finalState = store.clearIntent(committed)
        return SignOutcome.Signed(finalState, record)
    }

    sealed interface SignOutcome {
        data class Signed(val state: WalletStore.State, val record: ChainRecord) : SignOutcome
        data class KeyAlreadyBurnt(val requestedIndex: Int, val keysBurnt: Int) : SignOutcome
        data object NoKeysRemaining : SignOutcome
        data object NotProvisioned : SignOutcome
    }

    /** Record the terminal's co-signature once a receipt is accepted. */
    fun attachTerminalSignature(
        state: WalletStore.State,
        entryIndex: Int,
        terminalSignature: ByteArray,
    ): WalletStore.State {
        val updated = state.records.map {
            if (it.entry.index == entryIndex) it.copy(terminalSignature = terminalSignature) else it
        }
        return state.copy(records = updated).also { store.save(it) }
    }

    /** Build the bundle the terminal verifies (section 6). */
    fun buildBundle(state: WalletStore.State): TerminalVerifier.Bundle? {
        val voucher = state.voucher ?: return null
        val nextKeyIndex = state.keysBurnt
        val keys = HashMap<Int, ByteArray>()
        state.records.forEach { r ->
            state.publicKeysX509.getOrNull(r.entry.index)?.let { keys[r.entry.index] = it }
        }
        state.publicKeysX509.getOrNull(nextKeyIndex)?.let { keys[nextKeyIndex] = it }
        val proof = if (nextKeyIndex < state.publicKeysX509.size) {
            KeyBatch.merkleProof(state.publicKeysX509, nextKeyIndex)
        } else {
            emptyList()
        }
        return TerminalVerifier.Bundle(
            voucher = voucher,
            ledgerSignature = state.ledgerSignature,
            ledgerPublicKeyX509 = state.ledgerPublicKeyX509,
            records = state.records,
            nextKeyIndex = nextKeyIndex,
            publicKeysX509 = keys,
            merkleProof = proof,
        )
    }

    fun setOfflineDisabled(state: WalletStore.State, reason: String?): WalletStore.State =
        state.copy(offlineDisabledReason = reason).also { store.save(it) }

    /** The only "resynchronisation" available while the ledger is stubbed. */
    fun resynchronise(state: WalletStore.State): WalletStore.State =
        state.copy(offlineDisabledReason = null, pendingIntent = null).also { store.save(it) }

    internal fun storeHandle(): WalletStore = store
}
