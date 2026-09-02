package lk.sliit.syntpay.wallet.data

import android.content.Context
import lk.sliit.syntpay.shared.AllowanceVoucher
import lk.sliit.syntpay.shared.ChainRecord
import lk.sliit.syntpay.shared.SpendEntry
import lk.sliit.syntpay.shared.hexToBytes
import lk.sliit.syntpay.shared.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for the wallet, SYNTPAY_PROJECT.md section 7.
 *
 * Two things here carry the security of the whole design:
 *
 * 1. THERE IS NO BALANCE FIELD. Nothing in this file stores a spendable
 *    amount. The balance is recomputed from the voucher and the chain every
 *    time it is needed. There is no number here for an attacker to edit.
 *
 * 2. keysBurnt IS NOT THE CHAIN LENGTH. It is tracked separately and only ever
 *    increases, because it mirrors hardware state: a key with
 *    setMaxUsageCount(1) cannot be un-burnt. Deleting chain entries therefore
 *    leaves keysBurnt > records.size, which is exactly the gap the terminal
 *    detects. The Lab attacks edit `records` and deliberately leave keysBurnt
 *    alone, because that is precisely what a rooted attacker can and cannot do.
 *
 * Writes are crash-safe: every save goes to a temporary file which is then
 * renamed over the real one, so a process death mid-write cannot leave a
 * half-written chain behind.
 */
class WalletStore(context: Context) {

    private val dir: File = context.filesDir
    private val file = File(dir, "wallet-state.json")
    private val backupFile = File(dir, "wallet-state.backup.json")

    data class State(
        val walletId: String,
        val batchId: String,
        val voucher: AllowanceVoucher?,
        val ledgerSignature: ByteArray,
        val ledgerPublicKeyX509: ByteArray,
        val publicKeysX509: List<ByteArray>,
        val records: List<ChainRecord>,
        /** Monotonic. Mirrors burnt hardware keys; never derived from records. */
        val keysBurnt: Int,
        val strongBoxBacked: Boolean,
        val offlineDisabledReason: String?,
        /** Write-ahead intent: non-null means a signing attempt did not complete. */
        val pendingIntent: PendingIntent?,
    ) {
        val isProvisioned: Boolean get() = voucher != null
    }

    data class PendingIntent(
        val keyIndex: Int,
        val amountMinor: Long,
        val merchantId: String,
        val terminalId: String,
        val txnIdHex: String,
    )

    fun empty(): State = State(
        walletId = "",
        batchId = "",
        voucher = null,
        ledgerSignature = ByteArray(0),
        ledgerPublicKeyX509 = ByteArray(0),
        publicKeysX509 = emptyList(),
        records = emptyList(),
        keysBurnt = 0,
        strongBoxBacked = false,
        offlineDisabledReason = null,
        pendingIntent = null,
    )

    fun load(): State = if (!file.exists()) empty() else runCatching {
        parse(JSONObject(file.readText()))
    }.getOrElse { empty() }

    /** Atomic: write to a temp file, then rename over the target. */
    fun save(state: State) {
        val temp = File(dir, "wallet-state.json.tmp")
        temp.writeText(serialise(state).toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    // ---- write-ahead intent, section 3 recovery path ----

    /** Written BEFORE signing. */
    fun recordIntent(state: State, intent: PendingIntent): State =
        state.copy(pendingIntent = intent).also { save(it) }

    /** Cleared AFTER the entry is persisted. */
    fun clearIntent(state: State): State = state.copy(pendingIntent = null).also { save(it) }

    // ---- Lab support: an honest snapshot to restore from ----

    fun hasBackup(): Boolean = backupFile.exists()

    fun backup(state: State) {
        backupFile.writeText(serialise(state).toString())
    }

    fun restoreBackup(): State? {
        if (!backupFile.exists()) return null
        return runCatching { parse(JSONObject(backupFile.readText())) }
            .getOrNull()
            ?.also { save(it) }
    }

    fun wipe() {
        file.delete()
        backupFile.delete()
    }

    // ---- serialisation (hand-rolled; no Room, no Retrofit, section 1) ----

    private fun serialise(state: State): JSONObject = JSONObject().apply {
        put("walletId", state.walletId)
        put("batchId", state.batchId)
        put("keysBurnt", state.keysBurnt)
        put("strongBoxBacked", state.strongBoxBacked)
        put("ledgerSignature", state.ledgerSignature.toHex())
        put("ledgerPublicKeyX509", state.ledgerPublicKeyX509.toHex())
        put("offlineDisabledReason", state.offlineDisabledReason ?: JSONObject.NULL)
        state.voucher?.let { v ->
            put(
                "voucher",
                JSONObject().apply {
                    put("batchId", v.batchId)
                    put("keyRoot", v.keyRoot.toHex())
                    put("walletId", v.walletId)
                    put("allowanceMinor", v.allowanceMinor)
                    put("expiryBlock", v.expiryBlock)
                    put("issuedAtBlock", v.issuedAtBlock)
                    put("policyVersion", v.policyVersion)
                },
            )
        }
        put("publicKeysX509", JSONArray().apply { state.publicKeysX509.forEach { put(it.toHex()) } })
        put(
            "records",
            JSONArray().apply {
                state.records.forEach { r ->
                    put(
                        JSONObject().apply {
                            put("index", r.entry.index)
                            put("txnId", r.entry.txnId.toHex())
                            put("amountMinor", r.entry.amountMinor)
                            put("batchId", r.entry.batchId)
                            put("prevHash", r.entry.prevHash.toHex())
                            put("walletId", r.entry.walletId)
                            put("merchantId", r.entry.merchantId)
                            put("terminalId", r.entry.terminalId)
                            put("voucherRef", r.entry.voucherRef.toHex())
                            put("terminalTime", r.entry.terminalTime)
                            put("walletSignature", r.walletSignature.toHex())
                            put("terminalSignature", r.terminalSignature?.toHex() ?: JSONObject.NULL)
                        },
                    )
                }
            },
        )
        state.pendingIntent?.let { p ->
            put(
                "pendingIntent",
                JSONObject().apply {
                    put("keyIndex", p.keyIndex)
                    put("amountMinor", p.amountMinor)
                    put("merchantId", p.merchantId)
                    put("terminalId", p.terminalId)
                    put("txnIdHex", p.txnIdHex)
                },
            )
        }
    }

    private fun parse(json: JSONObject): State {
        val voucher = json.optJSONObject("voucher")?.let { v ->
            AllowanceVoucher(
                batchId = v.getString("batchId"),
                keyRoot = hexToBytes(v.getString("keyRoot")),
                walletId = v.getString("walletId"),
                allowanceMinor = v.getLong("allowanceMinor"),
                expiryBlock = v.getLong("expiryBlock"),
                issuedAtBlock = v.getLong("issuedAtBlock"),
                policyVersion = v.getInt("policyVersion"),
            )
        }
        val keys = json.optJSONArray("publicKeysX509")?.let { arr ->
            (0 until arr.length()).map { hexToBytes(arr.getString(it)) }
        } ?: emptyList()
        val records = json.optJSONArray("records")?.let { arr ->
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                ChainRecord(
                    entry = SpendEntry(
                        index = r.getInt("index"),
                        txnId = hexToBytes(r.getString("txnId")),
                        amountMinor = r.getLong("amountMinor"),
                        batchId = r.getString("batchId"),
                        prevHash = hexToBytes(r.getString("prevHash")),
                        walletId = r.getString("walletId"),
                        merchantId = r.getString("merchantId"),
                        terminalId = r.getString("terminalId"),
                        voucherRef = hexToBytes(r.getString("voucherRef")),
                        terminalTime = r.getLong("terminalTime"),
                    ),
                    walletSignature = hexToBytes(r.getString("walletSignature")),
                    terminalSignature = r.optString("terminalSignature", "")
                        .takeIf { it.isNotEmpty() && it != "null" }?.let { hexToBytes(it) },
                )
            }
        } ?: emptyList()
        val intent = json.optJSONObject("pendingIntent")?.let { p ->
            PendingIntent(
                keyIndex = p.getInt("keyIndex"),
                amountMinor = p.getLong("amountMinor"),
                merchantId = p.getString("merchantId"),
                terminalId = p.getString("terminalId"),
                txnIdHex = p.getString("txnIdHex"),
            )
        }
        return State(
            walletId = json.optString("walletId", ""),
            batchId = json.optString("batchId", ""),
            voucher = voucher,
            ledgerSignature = hexToBytes(json.optString("ledgerSignature", "")),
            ledgerPublicKeyX509 = hexToBytes(json.optString("ledgerPublicKeyX509", "")),
            publicKeysX509 = keys,
            records = records,
            keysBurnt = json.optInt("keysBurnt", 0),
            strongBoxBacked = json.optBoolean("strongBoxBacked", false),
            offlineDisabledReason = json.opt("offlineDisabledReason")
                ?.takeIf { it != JSONObject.NULL }?.toString(),
            pendingIntent = intent,
        )
    }
}
