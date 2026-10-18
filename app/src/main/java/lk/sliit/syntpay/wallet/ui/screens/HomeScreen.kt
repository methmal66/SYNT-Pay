package lk.sliit.syntpay.wallet.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.wallet.ui.WalletUiState
import lk.sliit.syntpay.wallet.ui.theme.SyntPayPalette

/**
 * Home, SYNTPAY_PROJECT.md section 7. The balance is the only large element, and it is
 * derived on every recomposition from the voucher and the chain.
 */
@Composable
fun HomeScreen(
    state: WalletUiState,
    onInitialise: () -> Unit,
    onPay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddFunds by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SYNT PAY", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            StatusPill(online = !state.demonstrationOffline)
        }

        if (!state.provisioned) {
            UnprovisionedBody(state, onInitialise)
        } else {
            ProvisionedBody(state, onPay) { showAddFunds = true }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showAddFunds) {
        AlertDialog(
            onDismissRequest = { showAddFunds = false },
            confirmButton = {
                TextButton(onClick = { showAddFunds = false }) { Text("Close") }
            },
            title = { Text("Add funds") },
            // Verbatim from section 7. Provisioning and funding look like one thing
            // but are not; "Initialise wallet" genuinely works, this does not.
            text = {
                Text(
                    "Bank integration pending — the custodial bank API is not yet " +
                        "available. A fixed demo allowance of LKR 5,000 is active for " +
                        "this build.",
                    fontSize = 13.sp,
                )
            },
        )
    }
}

@Composable
private fun UnprovisionedBody(state: WalletUiState, onInitialise: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No allowance yet", fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Initialising generates a batch of 20 single-use keys in this phone's " +
                "secure hardware and obtains an allowance voucher for them.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onInitialise,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (state.busy) "Generating keys…" else "Initialise wallet", fontSize = 15.sp)
        }
    }
}

@Composable
private fun ProvisionedBody(state: WalletUiState, onPay: () -> Unit, onAddFunds: () -> Unit) {
    val target = state.remainingMinor / 100.0
    val animated by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "balance",
    )

    Text(
        "Offline allowance remaining",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = String.format("%,.2f", animated),
        fontSize = 44.sp,
        fontWeight = FontWeight.Light,
        style = MaterialTheme.typography.displayLarge,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "of ${formatMinor(state.allowanceMinor)} escrowed",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    ThinProgressBar(
        fraction = if (state.allowanceMinor == 0L) 0f
        else state.remainingMinor.toFloat() / state.allowanceMinor.toFloat(),
    )

    val warning = state.offlineDisabledReason ?: state.gapDetected
    if (warning != null) {
        Spacer(Modifier.height(16.dp))
        SyntCard(background = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                if (state.offlineDisabledReason != null) "Offline spending disabled" else "Gap detected",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SyntPayPalette.Warning,
            )
            Spacer(Modifier.height(6.dp))
            // Verbatim. Never soften a reason string (section 7).
            Text(warning, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SyntCard(Modifier.weight(1f)) {
            CardLabel("Keys remaining")
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.keysRemaining} of ${state.keysTotal}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            KeyDots(total = state.keysTotal, burnt = state.keysBurnt)
        }
        SyntCard(Modifier.weight(1f)) {
            CardLabel("Offline payments")
            Spacer(Modifier.height(4.dp))
            Text("${state.paymentCount}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            CardLabel("spent ${formatMinor(state.spentMinor)}")
        }
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onPay,
        enabled = !state.offlineSpendingDisabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text("Pay", fontSize = 15.sp)
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onAddFunds,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Text("Add funds", fontSize = 15.sp)
    }

    Spacer(Modifier.height(18.dp))
    SyntCard {
        KeyValueRow("Wallet", state.walletId)
        KeyValueRow("Batch", state.batchId)
        KeyValueRow("Expiry block", state.expiryBlock.toString())
    }
    Box(Modifier.height(8.dp))
}
