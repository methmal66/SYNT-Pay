package lk.sliit.syntpay.wallet.ui.screens

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.shared.ChainRecord
import lk.sliit.syntpay.shared.KeyBatch
import lk.sliit.syntpay.shared.toHex
import lk.sliit.syntpay.wallet.ui.WalletUiState

/**
 * Trace, SYNTPAY_PROJECT.md section 7.
 *
 * Deliberately unstyled. This is evidence, not a product surface: monospace,
 * 12sp, nothing truncated and nothing prettified.
 */
@Composable
fun TraceScreen(
    state: WalletUiState,
    entryIndex: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val record = state.records.firstOrNull { it.entry.index == entryIndex }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    if (record == null) {
        Column(modifier.fillMaxSize().padding(20.dp)) {
            Text("No entry at index $entryIndex", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        return
    }

    val text = buildTrace(state, record)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = {
                exportMessage = exportReceiptJson(context, record, text)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export receipt JSON")
        }
        exportMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(28.dp))
    }
}

private fun buildTrace(state: WalletUiState, record: ChainRecord): String = buildString {
    val entry = record.entry
    val encoding = entry.canonicalEncoding()
    appendLine("entry index     : ${entry.index}")
    appendLine("signing key     : ${KeyBatch.alias(entry.batchId, entry.index)}")
    appendLine()
    appendLine("canonical CBOR  (${encoding.size} bytes)")
    appendLine(wrap(encoding.toHex()))
    appendLine()
    appendLine("encoding length : ${encoding.size}")
    appendLine("receipt id      : ${entry.receiptId().toHex()}")
    appendLine()
    appendLine("wallet signature (${record.walletSignature.size} bytes)")
    appendLine(wrap(record.walletSignature.toHex()))
    appendLine()
    appendLine("previous hash   : ${entry.prevHash.toHex()}")
    appendLine()
    appendLine("derived balance")
    val priorAmounts = state.records.filter { it.entry.index <= entry.index }.map { it.entry.amountMinor }
    appendLine(
        "  ${state.allowanceMinor} − (${priorAmounts.joinToString(" + ")}) = " +
            "${state.allowanceMinor - priorAmounts.sum()}",
    )
    appendLine()
    appendLine("terminal response")
    if (record.terminalSignature == null) {
        appendLine("  (none — this receipt has not been co-signed)")
    } else {
        appendLine("  co-signature (${record.terminalSignature.size} bytes)")
        appendLine(wrap(record.terminalSignature.toHex()))
    }
}

private fun wrap(hex: String, width: Int = 64): String =
    hex.chunked(width).joinToString("\n") { "  $it" }

private fun exportReceiptJson(context: Context, record: ChainRecord, trace: String): String {
    val entry = record.entry
    val json = buildString {
        appendLine("{")
        appendLine("  \"index\": ${entry.index},")
        appendLine("  \"txnID\": \"${entry.txnId.toHex()}\",")
        appendLine("  \"amount\": ${entry.amountMinor},")
        appendLine("  \"batchID\": \"${entry.batchId}\",")
        appendLine("  \"prevHash\": \"${entry.prevHash.toHex()}\",")
        appendLine("  \"walletID\": \"${entry.walletId}\",")
        appendLine("  \"merchantID\": \"${entry.merchantId}\",")
        appendLine("  \"terminalID\": \"${entry.terminalId}\",")
        appendLine("  \"voucherRef\": \"${entry.voucherRef.toHex()}\",")
        appendLine("  \"terminalTime\": ${entry.terminalTime},")
        appendLine("  \"canonicalCbor\": \"${entry.canonicalEncoding().toHex()}\",")
        appendLine("  \"receiptId\": \"${entry.receiptId().toHex()}\",")
        appendLine("  \"walletSignature\": \"${record.walletSignature.toHex()}\",")
        appendLine("  \"terminalSignature\": ${record.terminalSignature?.let { "\"${it.toHex()}\"" } ?: "null"}")
        appendLine("}")
    }
    return try {
        val name = "syntpay-receipt-${entry.index}-${entry.receiptId().toHex().take(8)}.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "export failed: no destination"
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        "exported to Downloads/$name"
    } catch (e: Exception) {
        "export failed: ${e.javaClass.simpleName}: ${e.message}"
    }
}
