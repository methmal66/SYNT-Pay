package lk.sliit.syntpay.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.shared.ChainRecord
import lk.sliit.syntpay.shared.toHex
import lk.sliit.syntpay.wallet.ui.WalletUiState
import lk.sliit.syntpay.wallet.ui.theme.SyntPayPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History, SYNTPAY_PROJECT.md section 7. Newest first. */
@Composable
fun HistoryScreen(
    state: WalletUiState,
    onViewTrace: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.records.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No offline payments yet",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.records.reversed()) { record ->
            HistoryRow(record = record, onViewTrace = { onViewTrace(record.entry.index) })
        }
    }
}

@Composable
private fun HistoryRow(record: ChainRecord, onViewTrace: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val settled = record.terminalSignature != null

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // A 1dp line down the left margin, suggesting the chain (section 7).
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(14.dp))
        SyntCard(Modifier.clickable { expanded = !expanded }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatMinor(record.entry.amountMinor),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${record.entry.merchantId} · ${formatTime(record.entry.terminalTime)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "#${record.entry.index}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Chip(if (settled) "settled" else "pending", settled)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    KeyValueRow("Receipt id", record.entry.receiptId().toHex())
                    KeyValueRow("Previous hash", record.entry.prevHash.toHex().take(24) + "…")
                    KeyValueRow("Signing key", "index ${record.entry.index}")
                    KeyValueRow("Terminal", record.entry.terminalId)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onViewTrace, modifier = Modifier.fillMaxWidth()) {
                        Text("View trace", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, positive: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(100))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (positive) SyntPayPalette.Accent else SyntPayPalette.Warning,
        )
    }
}

private fun formatTime(epochSeconds: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(epochSeconds * 1000L))
