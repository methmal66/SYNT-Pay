package lk.sliit.syntpay.wallet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.wallet.ui.WalletUiState
import lk.sliit.syntpay.wallet.ui.theme.SyntPayPalette

/** Status, SYNTPAY_PROJECT.md section 7. */
@Composable
fun StatusScreen(
    state: WalletUiState,
    onToggleOffline: (Boolean) -> Unit,
    onResynchronise: () -> Unit,
    onRunCapabilityReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        SyntCard {
            CardLabel("Device security")
            Spacer(Modifier.height(6.dp))
            Text(
                state.securityLevel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (state.strongBoxBacked) SyntPayPalette.Accent else SyntPayPalette.Warning,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.capabilityNote,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        SyntCard {
            CardLabel("Key batch")
            Spacer(Modifier.height(6.dp))
            if (!state.provisioned) {
                Text("not provisioned", fontSize = 15.sp)
            } else {
                KeyValueRow("Batch id", state.batchId)
                KeyValueRow("Used", "${state.keysBurnt} of ${state.keysTotal}")
                KeyValueRow("Expiry block", state.expiryBlock.toString())
                Spacer(Modifier.height(8.dp))
                KeyDots(total = state.keysTotal, burnt = state.keysBurnt)
            }
        }

        Spacer(Modifier.height(12.dp))
        SyntCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Demonstration offline", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Forces the offline state without airplane mode",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.demonstrationOffline, onCheckedChange = onToggleOffline)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onResynchronise,
            enabled = !state.demonstrationOffline,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Resynchronise")
        }

        Spacer(Modifier.height(24.dp))
        Text("Capability evidence", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Record this output for every handset tested, including the ones that " +
                "fail. It is the hardware coverage result.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRunCapabilityReport,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (state.busy) "Running…" else "Run capability report")
        }

        state.capabilityReport?.let { report ->
            Spacer(Modifier.height(12.dp))
            SyntCard(background = MaterialTheme.colorScheme.surfaceVariant) {
                // Not truncated, not prettified (section 7).
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { copyToClipboard(context, "SYNT PAY capability report", report) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy")
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
