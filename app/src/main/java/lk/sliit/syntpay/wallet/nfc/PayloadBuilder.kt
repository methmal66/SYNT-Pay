package lk.sliit.syntpay.wallet.nfc

import lk.sliit.syntpay.shared.KeyBatch
import lk.sliit.syntpay.shared.Protocol
import lk.sliit.syntpay.wallet.data.WalletStore

/**
 * Builds the byte array the wallet serves in chunks, SYNTPAY_PROJECT.md section 6.
 *
 * The layout itself lives in the SHARED Protocol file, so the wallet's builder
 * and the terminal's parser cannot drift apart.
 */
object PayloadBuilder {

    fun build(state: WalletStore.State, keysCached: Boolean): ByteArray? {
        val voucher = state.voucher ?: return null
        val nextKeyIndex = state.keysBurnt

        // Only the keys at indices present in the chain, plus the next unused
        // one, are sent -- not all twenty (section 6).
        val keys = HashMap<Int, ByteArray>()
        state.records.forEach { record ->
            state.publicKeysX509.getOrNull(record.entry.index)?.let {
                keys[record.entry.index] = it
            }
        }
        state.publicKeysX509.getOrNull(nextKeyIndex)?.let { keys[nextKeyIndex] = it }

        val proof = if (nextKeyIndex < state.publicKeysX509.size) {
            KeyBatch.merkleProof(state.publicKeysX509, nextKeyIndex)
        } else {
            emptyList()
        }

        return Protocol.encodePayload(
            Protocol.Payload(
                voucher = voucher,
                ledgerSignature = state.ledgerSignature,
                ledgerPublicKeyX509 = state.ledgerPublicKeyX509,
                nextKeyIndex = nextKeyIndex,
                records = state.records,
                keysX509 = keys,
                merkleProof = proof,
            ),
            includeKeys = !keysCached,
        )
    }
}
