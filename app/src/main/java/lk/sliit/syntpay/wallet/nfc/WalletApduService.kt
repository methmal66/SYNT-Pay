package lk.sliit.syntpay.wallet.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import lk.sliit.syntpay.shared.Protocol
import lk.sliit.syntpay.wallet.data.WalletRepository
import lk.sliit.syntpay.wallet.data.WalletStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The wallet emulates a card, SYNTPAY_PROJECT.md section 6.
 *
 * processCommandApdu RUNS ON THE MAIN THREAD and must return quickly. Signing a
 * spend entry is far too slow to do inline, so SESSION hands the work to a
 * background thread and returns 6A 88 (not ready) until the payload is built.
 * The terminal retries. Nothing here blocks the main thread waiting on
 * cryptography.
 *
 * By the time the terminal starts pulling chunks the payload is a fixed byte
 * array in memory, so chunk serving is pure I/O and the signing cost lands in
 * one measurable place.
 */
class WalletApduService : HostApduService() {

    companion object {
        private const val TAG = "SyntPayHce"

        /** Set by the UI so the Pay screen can follow the exchange. */
        val lastEvent = AtomicReference<Event?>(null)

        sealed interface Event {
            data class Session(val amountMinor: Long, val merchantId: String, val terminalId: String) : Event
            data class Chunk(val index: Int, val total: Int) : Event
            data class Result(val accepted: Boolean, val code: String, val detail: String) : Event
            data class SelfRefused(val reason: Byte) : Event
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var repository: WalletRepository

    @Volatile private var payload: ByteArray? = null
    @Volatile private var building = false
    @Volatile private var selfRefusal: Byte? = null
    @Volatile private var chunkSize = 240
    @Volatile private var signedState: WalletStore.State? = null
    @Volatile private var signedEntryIndex: Int = -1

    override fun onCreate() {
        super.onCreate()
        repository = WalletRepository(applicationContext)
    }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "deactivated: $reason")
        payload = null
        building = false
        selfRefusal = null
        signedState = null
        signedEntryIndex = -1
    }

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        if (apdu == null || apdu.size < 4) return Protocol.SW_FILE_NOT_FOUND

        // SELECT
        if (Protocol.selectsOurAid(apdu)) {
            Log.i(TAG, "SELECT ok")
            payload = null
            selfRefusal = null
            return byteArrayOf(Protocol.VERSION, 0x00) + Protocol.SW_OK
        }

        if (apdu[0] != Protocol.CLA_PROPRIETARY) return Protocol.SW_INS_NOT_SUPPORTED

        return when (apdu[1]) {
            Protocol.INS_SESSION -> handleSession(apdu)
            Protocol.INS_GET_CHUNK -> handleGetChunk(apdu)
            Protocol.INS_RESULT -> handleResult(apdu)
            else -> Protocol.SW_INS_NOT_SUPPORTED
        }
    }

    private fun handleSession(apdu: ByteArray): ByteArray {
        selfRefusal?.let { return Protocol.SW_WALLET_REFUSED + byteArrayOf(it) }

        val ready = payload
        if (ready != null) return sessionResponse(ready)

        // Already working on it: tell the terminal to retry rather than block
        // the main thread (section 6).
        if (building) return Protocol.SW_NOT_READY

        val body = apdu.copyOfRange(5, minOf(apdu.size, 5 + (apdu[4].toInt() and 0xFF)))
        val request = try {
            Protocol.decodeSessionRequest(body)
        } catch (e: Exception) {
            Log.w(TAG, "malformed SESSION: ${e.message}")
            return Protocol.SW_FILE_NOT_FOUND
        }

        building = true
        lastEvent.set(
            Event.Session(request.amountMinor, request.merchantId, request.terminalId),
        )
        worker.execute { buildPayload(request) }
        return Protocol.SW_NOT_READY
    }

    /** Runs off the main thread. Signs, then freezes the payload. */
    private fun buildPayload(request: Protocol.SessionRequest) {
        try {
            val state = repository.load()

            repository.selfRefusalReason(state, request.amountMinor)?.let { reason ->
                selfRefusal = reason
                lastEvent.set(Event.SelfRefused(reason))
                Log.i(TAG, "wallet refused itself: ${Protocol.SelfRefusal.describe(reason)}")
                return
            }

            // Sign the new entry here, so the cost is measured in one place.
            val outcome = repository.signPayment(
                state = state,
                amountMinor = request.amountMinor,
                merchantId = request.merchantId,
                terminalId = request.terminalId,
                txnId = request.txnId,
                terminalTime = System.currentTimeMillis() / 1000L,
            )

            val presented = when (outcome) {
                is WalletRepository.SignOutcome.Signed -> {
                    signedState = outcome.state
                    signedEntryIndex = outcome.record.entry.index
                    outcome.state
                }

                is WalletRepository.SignOutcome.KeyAlreadyBurnt -> {
                    // The history was edited: the key for this chain position is
                    // already burnt. Present the chain exactly as it stands and
                    // let the terminal's gap check speak. This is the mechanism
                    // working, not an error to hide.
                    Log.i(
                        TAG,
                        "key ${outcome.requestedIndex} already burnt (keysBurnt=${outcome.keysBurnt}); " +
                            "presenting the chain unchanged so the gap is visible",
                    )
                    state
                }

                WalletRepository.SignOutcome.NoKeysRemaining -> {
                    selfRefusal = Protocol.SelfRefusal.NO_KEYS_REMAINING
                    lastEvent.set(Event.SelfRefused(Protocol.SelfRefusal.NO_KEYS_REMAINING))
                    return
                }

                WalletRepository.SignOutcome.NotProvisioned -> {
                    selfRefusal = Protocol.SelfRefusal.NOT_PROVISIONED
                    lastEvent.set(Event.SelfRefused(Protocol.SelfRefusal.NOT_PROVISIONED))
                    return
                }
            }

            payload = PayloadBuilder.build(presented, request.keysCached)
            Log.i(TAG, "payload ready: ${payload?.size} bytes, ${presented.records.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "payload build failed", e)
            selfRefusal = Protocol.SelfRefusal.OFFLINE_DISABLED
        } finally {
            building = false
        }
    }

    private fun sessionResponse(ready: ByteArray): ByteArray {
        val state = signedState ?: repository.load()
        return Protocol.encodeSessionResponse(
            Protocol.SessionResponse(
                chainLength = state.records.size,
                nextKeyIndex = state.keysBurnt,
                remainingMinor = repository.remaining(state),
                totalPayloadLength = ready.size,
            ),
        ) + Protocol.SW_OK
    }

    private fun handleGetChunk(apdu: ByteArray): ByteArray {
        val ready = payload ?: return Protocol.SW_NOT_READY
        val index = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        val chunk = Protocol.chunkAt(ready, index, chunkSize)
            ?: return Protocol.SW_CHUNK_OUT_OF_RANGE
        lastEvent.set(Event.Chunk(index + 1, Protocol.chunkCount(ready.size, chunkSize)))
        return chunk + Protocol.SW_OK
    }

    private fun handleResult(apdu: ByteArray): ByteArray {
        val body = apdu.copyOfRange(5, minOf(apdu.size, 5 + (apdu[4].toInt() and 0xFF)))
        val result = try {
            Protocol.decodeResultRequest(body)
        } catch (e: Exception) {
            return Protocol.SW_FILE_NOT_FOUND
        }
        Log.i(TAG, "RESULT accepted=${result.accepted} code=${result.code} detail=${result.detail}")
        lastEvent.set(Event.Result(result.accepted, result.code, result.detail))

        // Record the terminal's co-signature against the entry we just signed.
        val state = signedState
        if (result.accepted && state != null && signedEntryIndex >= 0 && result.terminalSignature.isNotEmpty()) {
            worker.execute {
                runCatching {
                    repository.attachTerminalSignature(state, signedEntryIndex, result.terminalSignature)
                }
            }
        }
        return Protocol.SW_OK
    }
}
