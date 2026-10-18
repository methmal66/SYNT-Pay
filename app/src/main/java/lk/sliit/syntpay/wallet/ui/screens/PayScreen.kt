package lk.sliit.syntpay.wallet.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.wallet.ui.PayState
import lk.sliit.syntpay.wallet.ui.WalletUiState
import lk.sliit.syntpay.wallet.ui.theme.SyntPayPalette

/**
 * Pay, SYNTPAY_PROJECT.md section 7.
 *
 * THERE IS NO AMOUNT KEYPAD HERE, deliberately. The terminal supplies the
 * amount, the merchant, the terminal id and the transaction id. The wallet never
 * invents any of them.
 */
@Composable
fun PayScreen(
    state: WalletUiState,
    nfcEnabled: Boolean,
    nfcPresent: Boolean,
    onDone: () -> Unit,
    onViewTrace: (Int) -> Unit,
    onSimulatePay: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!nfcPresent) {
            CentredNotice(
                title = "NFC not available",
                body = "This handset has no NFC radio. Use simulator below to test complete cryptographic payment flow on this device.",
                tint = SyntPayPalette.Warning,
            ) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onSimulatePay,
                    enabled = state.provisioned && !state.offlineSpendingDisabled,
                ) {
                    Text("Simulate payment (LKR 25.00)")
                }
            }
            return@Column
        }
        if (!nfcEnabled) {
            CentredNotice(
                title = "NFC is switched off",
                body = "Turn NFC on to present this wallet to a terminal.",
                tint = SyntPayPalette.Warning,
            ) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }) { Text("Open NFC settings") }
            }
            return@Column
        }

        when (val pay = state.pay) {
            is PayState.Idle -> IdleBody(state)
            is PayState.SessionReceived -> SessionBody(pay)
            is PayState.Signing -> CentredNotice("Signing", "Burning the next single-use key.")
            is PayState.Transferring -> TransferBody(pay)
            is PayState.Accepted -> AcceptedBody(pay, onDone, onViewTrace)
            is PayState.Refused -> RefusedBody(pay, onDone)
            is PayState.LocallyRefused -> LocallyRefusedBody(pay, onDone)
        }
    }
}

@Composable
private fun IdleBody(state: WalletUiState) {
    Spacer(Modifier.height(72.dp))
    PulsingRings(Modifier.size(180.dp))
    Spacer(Modifier.height(28.dp))
    Text("Hold near the terminal", fontSize = 18.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Screen must stay on and unlocked",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.offlineSpendingDisabled) {
        Spacer(Modifier.height(20.dp))
        SyntCard(background = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                state.offlineDisabledReason.orEmpty(),
                fontSize = 12.sp,
                color = SyntPayPalette.Warning,
            )
        }
    }
}

@Composable
private fun SessionBody(pay: PayState.SessionReceived) {
    Spacer(Modifier.height(56.dp))
    Text(formatMinor(pay.amountMinor), fontSize = 36.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(16.dp))
    SyntCard {
        KeyValueRow("Merchant", pay.merchantId)
        KeyValueRow("Terminal", pay.terminalId)
        KeyValueRow(
            "Remaining after this payment",
            formatMinor(pay.remainingAfter),
            valueColor = if (pay.exceedsAllowance) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun TransferBody(pay: PayState.Transferring) {
    Spacer(Modifier.height(96.dp))
    Text("Transferring", fontSize = 18.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    ThinProgressBar(
        fraction = if (pay.totalChunks == 0) 0f else pay.chunk.toFloat() / pay.totalChunks,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Transferring ${pay.chunk} of ${pay.totalChunks}",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AcceptedBody(pay: PayState.Accepted, onDone: () -> Unit, onViewTrace: (Int) -> Unit) {
    Spacer(Modifier.height(56.dp))
    Text("Accepted", fontSize = 24.sp, color = SyntPayPalette.Accent, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
    Text(formatMinor(pay.amountMinor), fontSize = 36.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(18.dp))
    SyntCard {
        KeyValueRow("Merchant", pay.merchantId)
        KeyValueRow("Chain index", "#${pay.chainIndex}")
        KeyValueRow("Receipt id", pay.receiptIdHex.take(16))
        KeyValueRow("Remaining", formatMinor(pay.remainingAfter))
    }
    Spacer(Modifier.height(14.dp))
    Text(
        // Verbatim, section 7.
        "The ledger has not been updated. This receipt uploads when either device reconnects.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = { onViewTrace(pay.chainIndex) }, modifier = Modifier.fillMaxWidth()) {
        Text("View trace")
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun RefusedBody(pay: PayState.Refused, onDone: () -> Unit) {
    Spacer(Modifier.height(56.dp))
    Text("Refused", fontSize = 24.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
    Text(formatMinor(pay.amountMinor), fontSize = 36.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(18.dp))
    SyntCard {
        CardLabel("Code")
        Text(pay.code, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(10.dp))
        CardLabel("Reason")
        // Verbatim. Never softened (section 7).
        Text(pay.reason, fontSize = 13.sp)
        if (pay.code == "REFUSE_GAP") {
            Spacer(Modifier.height(10.dp))
            Text(
                "The terminal detected concealed spending.",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun LocallyRefusedBody(pay: PayState.LocallyRefused, onDone: () -> Unit) {
    Spacer(Modifier.height(72.dp))
    Text("Cannot pay", fontSize = 22.sp, color = SyntPayPalette.Warning, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(14.dp))
    SyntCard {
        Text(pay.reason, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "nothing was sent to the terminal.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun CentredNotice(
    title: String,
    body: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    extra: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = tint)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            extra()
        }
    }
}
