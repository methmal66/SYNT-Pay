package lk.sliit.syntpay.wallet.ui

import android.content.Context
import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lk.sliit.syntpay.wallet.ui.screens.HistoryScreen
import lk.sliit.syntpay.wallet.ui.screens.HomeScreen
import lk.sliit.syntpay.wallet.ui.screens.LabScreen
import lk.sliit.syntpay.wallet.ui.screens.PayScreen
import lk.sliit.syntpay.wallet.ui.screens.StatusScreen
import lk.sliit.syntpay.wallet.ui.screens.TraceScreen
import lk.sliit.syntpay.wallet.ui.theme.SyntPayWalletTheme

/**
 * Five bottom-nav destinations plus a Trace view reached from a result or a
 * history row (SYNTPAY_PROJECT.md section 7). No navigation library: section 1 forbids one,
 * and a sealed class with a `when` is all five destinations need.
 */
sealed class Destination(val title: String, val icon: ImageVector) {
    data object Home : Destination("Home", Icons.Default.Home)
    data object Pay : Destination("Pay", Icons.Default.Payment)
    data object History : Destination("History", Icons.Default.History)
    data object Status : Destination("Status", Icons.Default.Info)
    data object Lab : Destination("Lab", Icons.Default.Code)
}

private val destinations = listOf(
    Destination.Home,
    Destination.Pay,
    Destination.History,
    Destination.Status,
    Destination.Lab,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletApp(viewModel: WalletViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var current by remember { mutableStateOf<Destination>(Destination.Home) }
    var traceIndex by remember { mutableStateOf<Int?>(null) }

    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }

    SyntPayWalletTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (traceIndex != null) {
                    TopAppBar(
                        title = { Text("Trace", fontSize = 16.sp) },
                        navigationIcon = {
                            IconButton(onClick = { traceIndex = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                }
            },
            bottomBar = {
                if (traceIndex == null) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = current == destination,
                                onClick = { current = destination },
                                icon = { Icon(destination.icon, contentDescription = destination.title) },
                                label = { Text(destination.title, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { inner ->
            val modifier = Modifier.fillMaxSize().padding(inner)
            val index = traceIndex
            if (index != null) {
                TraceScreen(state = state, entryIndex = index, modifier = modifier)
                return@Scaffold
            }

            when (current) {
                Destination.Home -> HomeScreen(
                    state = state,
                    onInitialise = viewModel::initialiseWallet,
                    onPay = { current = Destination.Pay },
                    modifier = modifier,
                )

                Destination.Pay -> PayScreen(
                    state = state,
                    nfcPresent = adapter != null,
                    nfcEnabled = adapter?.isEnabled == true,
                    onDone = viewModel::resetPay,
                    onViewTrace = { traceIndex = it },
                    onSimulatePay = { viewModel.simulatePayment() },
                    modifier = modifier,
                )

                Destination.History -> HistoryScreen(
                    state = state,
                    onViewTrace = { traceIndex = it },
                    modifier = modifier,
                )

                Destination.Status -> StatusScreen(
                    state = state,
                    onToggleOffline = viewModel::setDemonstrationOffline,
                    onResynchronise = viewModel::resynchronise,
                    onRunCapabilityReport = viewModel::runCapabilityReport,
                    modifier = modifier,
                )

                Destination.Lab -> LabScreen(
                    state = state,
                    onDeleteLastTwo = viewModel::attackDeleteLastTwo,
                    onRollBack = viewModel::attackRollBack,
                    onEditAmount = viewModel::attackEditAmount,
                    onCorruptLink = viewModel::attackCorruptLink,
                    onRestore = viewModel::restoreHonestState,
                    onRunCorpus = viewModel::runAttackCorpus,
                    onSimulatePayment = {
                        viewModel.simulatePayment()
                        current = Destination.Pay
                    },
                    modifier = modifier,
                )
            }
        }
    }
}

private fun Context.nfcAdapter(): NfcAdapter? = NfcAdapter.getDefaultAdapter(this)
