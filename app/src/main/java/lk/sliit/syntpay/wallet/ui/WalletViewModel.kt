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
