package lk.sliit.syntpay.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.sliit.syntpay.shared.ChainRecord
import lk.sliit.syntpay.shared.KeyBatch
import lk.sliit.syntpay.shared.toHex
import lk.sliit.syntpay.wallet.data.WalletRepository
import lk.sliit.syntpay.wallet.data.WalletStore
import lk.sliit.syntpay.wallet.lab.WalletLab

/**
 * SYNTPAY_PROJECT.md section 7.
 *
 * NOTE WHAT IS NOT HERE: there is no balance field that anything writes to.
 * [WalletUiState.remainingMinor] is recomputed from the voucher and the chain
 * in [snapshot] every time a snapshot is taken. If you ever add a setter for
 * it, you have put a number on the phone for an attacker to edit.
 */
sealed interface PayState {
    data object Idle : PayState

    data class SessionReceived(
        val amountMinor: Long,
        val merchantId: String,
        val terminalId: String,
        val remainingAfter: Long,
        val exceedsAllowance: Boolean,
    ) : PayState

    data object Signing : PayState

    data class Transferring(val chunk: Int, val totalChunks: Int) : PayState

    data class Accepted(
        val amountMinor: Long,
        val merchantId: String,
        val chainIndex: Int,
        val receiptIdHex: String,
        val remainingAfter: Long,
    ) : PayState

    data class Refused(
        val amountMinor: Long,
        val code: String,
        /** Displayed verbatim, never softened (section 7). */
        val reason: String,
    ) : PayState

    /** The wallet refused before presenting anything; nothing was sent. */
    data class LocallyRefused(val reason: String) : PayState
}

data class WalletUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val provisioned: Boolean = false,
    val walletId: String = "",
    val batchId: String = "",
    val allowanceMinor: Long = 0L,
    /** Derived, never stored. */
    val remainingMinor: Long = 0L,
    val spentMinor: Long = 0L,
    val paymentCount: Int = 0,
    val keysBurnt: Int = 0,
    val keysTotal: Int = KeyBatch.BATCH_SIZE,
    val expiryBlock: Long = 0L,
    val securityLevel: String = "checking…",
    val strongBoxBacked: Boolean = false,
    val capabilityNote: String = "",
    val demonstrationOffline: Boolean = true,
    val offlineDisabledReason: String? = null,
    val gapDetected: String? = null,
    val records: List<ChainRecord> = emptyList(),
    val capabilityReport: String? = null,
    val labMessage: String? = null,
    val corpus: WalletLab.CorpusReport? = null,
    val hasBackup: Boolean = false,
    val pay: PayState = PayState.Idle,
) {
    val keysRemaining: Int get() = keysTotal - keysBurnt
    val offlineSpendingDisabled: Boolean get() = offlineDisabledReason != null
}

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WalletRepository(application)
    private val lab = WalletLab()

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    private var stored: WalletStore.State = repository.load()

    init {
        viewModelScope.launch {
            val recovered = withContext(Dispatchers.IO) { repository.recoverOnLaunch() }
            stored = recovered
            _state.value = snapshot(recovered).copy(loading = false)
        }

        // Observe NFC Host Card Emulation events from WalletApduService
        viewModelScope.launch {
            while (true) {
                val event = lk.sliit.syntpay.wallet.nfc.WalletApduService.lastEvent.getAndSet(null)
                if (event != null) {
                    when (event) {
                        is lk.sliit.syntpay.wallet.nfc.WalletApduService.Companion.Event.Session -> {
                            onSessionReceived(event.amountMinor, event.merchantId, event.terminalId)
                        }
                        is lk.sliit.syntpay.wallet.nfc.WalletApduService.Companion.Event.Chunk -> {
                            _state.value = _state.value.copy(
                                pay = PayState.Transferring(event.index, event.total)
                            )
                        }
                        is lk.sliit.syntpay.wallet.nfc.WalletApduService.Companion.Event.Result -> {
                            stored = repository.load()
                            val lastRecord = stored.records.lastOrNull()
                            if (event.accepted) {
                                val receiptId = lastRecord?.entry?.receiptId()?.toHex().orEmpty()
                                val chainIdx = lastRecord?.entry?.index ?: 0
                                _state.value = snapshot(stored).copy(
                                    pay = PayState.Accepted(
                                        amountMinor = lastRecord?.entry?.amountMinor ?: 0L,
                                        merchantId = lastRecord?.entry?.merchantId ?: "Terminal",
                                        chainIndex = chainIdx,
                                        receiptIdHex = receiptId,
                                        remainingAfter = repository.remaining(stored),
                                    )
                                )
                            } else {
                                _state.value = snapshot(stored).copy(
                                    pay = PayState.Refused(
                                        amountMinor = lastRecord?.entry?.amountMinor ?: 0L,
                                        code = event.code,
                                        reason = event.detail,
                                    )
                                )
                            }
                        }
                        is lk.sliit.syntpay.wallet.nfc.WalletApduService.Companion.Event.SelfRefused -> {
                            _state.value = _state.value.copy(
                                pay = PayState.LocallyRefused(
                                    lk.sliit.syntpay.shared.Protocol.SelfRefusal.describe(event.reason)
                                )
                            )
                        }
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    /** Recompute every derived figure from the voucher and the chain. */
    private fun snapshot(s: WalletStore.State, base: WalletUiState = _state.value): WalletUiState {
        val context = getApplication<Application>()
        val remaining = repository.remaining(s)
        val gap = if (s.keysBurnt > s.records.size) {
            "This wallet reports ${s.keysBurnt} burnt keys but only ${s.records.size} " +
                (if (s.records.size == 1) "entry" else "entries") +
                " in its history. A terminal will refuse it with REFUSE_GAP."
        } else {
            null
        }
        return base.copy(
            provisioned = s.isProvisioned,
            walletId = s.walletId,
            batchId = s.batchId,
            allowanceMinor = s.voucher?.allowanceMinor ?: 0L,
            remainingMinor = remaining,
            spentMinor = repository.spent(s),
            paymentCount = s.records.size,
            keysBurnt = s.keysBurnt,
            expiryBlock = s.voucher?.expiryBlock ?: 0L,
            securityLevel = if (s.isProvisioned && !s.strongBoxBacked) {
                "Trusted Execution Environment (TEE)"
            } else {
                KeyBatch.securityLevel(context)
            },
            strongBoxBacked = s.strongBoxBacked || KeyBatch.hasStrongBox(context),
            capabilityNote = if (KeyBatch.hasStrongBox(context)) {
                "This handset has a StrongBox HSM. Single-use keys are enforced in " +
                    "dedicated secure hardware."
            } else {
                "No StrongBox on this handset. Single-use keys are enforced by the " +
                    "Trusted Execution Environment instead."
            },
            offlineDisabledReason = s.offlineDisabledReason,
            gapDetected = gap,
            records = s.records,
            hasBackup = repository.storeHandle().hasBackup(),
        )
    }

    private fun refresh() {
        _state.value = snapshot(stored)
    }

    // ---- provisioning ----

    fun initialiseWallet() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val provisioned = withContext(Dispatchers.Default) { repository.provision() }
            stored = provisioned
            _state.value = snapshot(provisioned).copy(busy = false, labMessage = null)
        }
    }

    fun resetWallet() {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { repository.reset() }
            stored = cleared
            _state.value = snapshot(cleared).copy(busy = false)
        }
    }

    // ---- status ----

    fun setDemonstrationOffline(offline: Boolean) {
        _state.value = _state.value.copy(demonstrationOffline = offline)
    }

    fun resynchronise() {
        viewModelScope.launch {
            stored = withContext(Dispatchers.IO) { repository.resynchronise(stored) }
            refresh()
        }
    }

    fun runCapabilityReport() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) {
                lab.runCapabilityReport(getApplication())
            }
            _state.value = _state.value.copy(capabilityReport = report, busy = false)
        }
    }

    // ---- lab ----

    private fun applyAttack(block: (WalletStore.State) -> Pair<WalletStore.State, String>) {
        viewModelScope.launch {
            val (mutated, message) = withContext(Dispatchers.Default) { block(stored) }
            if (mutated !== stored) {
                withContext(Dispatchers.IO) { repository.storeHandle().save(mutated) }
                stored = mutated
            }
            _state.value = snapshot(stored).copy(labMessage = message)
        }
    }

    fun attackDeleteLastTwo() = applyAttack { lab.deleteLastPayments(it, 2) }
    fun attackRollBack() = applyAttack { lab.rollBackToEarlierState(it) }
    fun attackEditAmount() = applyAttack { lab.editStoredAmount(it) }
    fun attackCorruptLink() = applyAttack { lab.corruptPreviousHash(it) }

    fun restoreHonestState() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { repository.storeHandle().restoreBackup() }
            if (restored == null) {
                _state.value = _state.value.copy(labMessage = "No honest snapshot has been stored yet.")
            } else {
                stored = restored
                _state.value = snapshot(restored)
                    .copy(labMessage = "Restored the honest snapshot taken at provisioning.")
            }
        }
    }

    fun runAttackCorpus() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) { lab.runAttackCorpus() }
            _state.value = _state.value.copy(corpus = report, busy = false)
        }
    }

    // ---- pay (driven by the NFC layer; harness-driven until Block 15) ----

    fun onSessionReceived(amountMinor: Long, merchantId: String, terminalId: String) {
        val remaining = _state.value.remainingMinor
        _state.value = _state.value.copy(
            pay = PayState.SessionReceived(
                amountMinor = amountMinor,
                merchantId = merchantId,
                terminalId = terminalId,
                remainingAfter = remaining - amountMinor,
                exceedsAllowance = amountMinor > remaining,
            ),
        )
    }

    fun resetPay() {
        _state.value = _state.value.copy(pay = PayState.Idle)
    }

    /**
     * Simulate an honest payment and verify it using TerminalVerifier.
     * Enables end-to-end payment demonstration without requiring an external NFC POS device.
     */
    fun simulatePayment(amountMinor: Long = 2500L, merchantId: String = "Org1MSP", terminalId: String = "term-demo01") {
        if (_state.value.busy || !_state.value.provisioned || _state.value.offlineSpendingDisabled) return
        _state.value = _state.value.copy(busy = true, pay = PayState.Signing)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val bundleBefore = repository.buildBundle(stored)
                if (bundleBefore == null) {
                    return@withContext Pair(stored, PayState.LocallyRefused("Wallet not provisioned"))
                }
                val txnId = repository.newTxnId()
                val outcome = repository.signPayment(
                    state = stored,
                    amountMinor = amountMinor,
                    merchantId = merchantId,
                    terminalId = terminalId,
                    txnId = txnId,
                    terminalTime = System.currentTimeMillis() / 1000L,
                )
                when (outcome) {
                    is WalletRepository.SignOutcome.Signed -> {
                        val verifyOutcome = lk.sliit.syntpay.shared.TerminalVerifier.verify(
                            bundle = bundleBefore,
                            proposed = outcome.record.entry,
                            params = lk.sliit.syntpay.shared.TerminalVerifier.Params(lastKnownBlock = 95_000L),
                        )
                        when (verifyOutcome) {
                            is lk.sliit.syntpay.shared.TerminalVerifier.Outcome.Accept -> {
                                val terminalSig = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                                val finalState = repository.attachTerminalSignature(
                                    outcome.state,
                                    outcome.record.entry.index,
                                    terminalSig,
                                )
                                Pair(
                                    finalState,
                                    PayState.Accepted(
                                        amountMinor = amountMinor,
                                        merchantId = merchantId,
                                        chainIndex = outcome.record.entry.index,
                                        receiptIdHex = outcome.record.entry.receiptId().toHex(),
                                        remainingAfter = repository.remaining(finalState),
                                    ),
                                )
                            }
                            is lk.sliit.syntpay.shared.TerminalVerifier.Outcome.Refuse -> {
                                Pair(
                                    outcome.state,
                                    PayState.Refused(
                                        amountMinor = amountMinor,
                                        code = verifyOutcome.code.name,
                                        reason = verifyOutcome.detail,
                                    ),
                                )
                            }
                        }
                    }
                    is WalletRepository.SignOutcome.KeyAlreadyBurnt -> {
                        Pair(stored, PayState.Refused(amountMinor, "REFUSE_GAP", "Key at index already burnt"))
                    }
                    WalletRepository.SignOutcome.NoKeysRemaining -> {
                        Pair(stored, PayState.LocallyRefused("No single-use keys remaining"))
                    }
                    WalletRepository.SignOutcome.NotProvisioned -> {
                        Pair(stored, PayState.LocallyRefused("Wallet not provisioned"))
                    }
                }
            }
            stored = result.first
            _state.value = snapshot(stored).copy(busy = false, pay = result.second)
        }
    }

    internal fun repositoryHandle(): WalletRepository = repository
    internal fun storedState(): WalletStore.State = stored
    internal fun applyStored(s: WalletStore.State) {
        stored = s
        refresh()
    }

    internal fun setPay(payState: PayState) {
        _state.value = _state.value.copy(pay = payState)
    }
}
