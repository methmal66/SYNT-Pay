package lk.sliit.syntpay.wallet.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.wallet.ui.WalletUiState
import lk.sliit.syntpay.wallet.ui.theme.SyntPayPalette

/** Lab, SYNTPAY_PROJECT.md section 7. */
@Composable
fun LabScreen(
    state: WalletUiState,
    onDeleteLastTwo: () -> Unit,
    onRollBack: () -> Unit,
    onEditAmount: () -> Unit,
    onCorruptLink: () -> Unit,
    onRestore: () -> Unit,
    onRunCorpus: () -> Unit,
    onSimulatePayment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        // Verbatim from section 7.
        Text(
            "Each attack edits stored state without touching the burnt keys — " +
                "exactly what an attacker on a rooted device can do.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        LabButton(
            "Simulate honest payment (LKR 25.00)",
            enabled = state.provisioned && !state.offlineSpendingDisabled,
            onClick = onSimulatePayment,
        )
        LabButton("Delete last 2 payments", enabled = state.provisioned, onClick = onDeleteLastTwo)
        LabButton("Roll back to an earlier state", enabled = state.provisioned, onClick = onRollBack)
        LabButton("Edit a stored amount", enabled = state.provisioned, onClick = onEditAmount)
        LabButton("Corrupt a previous-hash link", enabled = state.provisioned, onClick = onCorruptLink)
        LabButton("Restore honest state", enabled = state.hasBackup, onClick = onRestore)

        state.labMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            SyntCard(background = MaterialTheme.colorScheme.surfaceVariant) {
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Attack corpus", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Each variant is a real signed chain, mutated and then put through the " +
                "same nine-step verifier the terminal runs. The codes below are what " +
                "the verifier actually returned.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRunCorpus,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (state.busy) "Running…" else "Run attack corpus")
        }

        state.corpus?.let { corpus ->
            Spacer(Modifier.height(12.dp))
            SyntCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Attack", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Result", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                corpus.results.forEach { result ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(result.label, fontSize = 12.sp)
                            Text(
                                result.actualCode,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (result.passed) "PASS" else "FAIL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (result.passed) SyntPayPalette.Accent else SyntPayPalette.Error,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    corpus.summary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (corpus.allPassed) SyntPayPalette.Accent else SyntPayPalette.Error,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun LabButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(46.dp).padding(bottom = 2.dp),
    ) {
        Text(label, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
}
