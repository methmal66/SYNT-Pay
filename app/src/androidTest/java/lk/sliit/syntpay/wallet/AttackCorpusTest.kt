package lk.sliit.syntpay.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import lk.sliit.syntpay.shared.TerminalVerifier
import lk.sliit.syntpay.wallet.lab.WalletLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Block 7's gate: "7 of 7 attacks handled correctly" (SYNTPAY_PROJECT.md section 10).
 *
 * This asserts on what the REAL nine-step verifier returns for each mutation of
 * a really-signed chain. It is not a table of expected answers checked against
 * itself.
 */
@RunWith(AndroidJUnit4::class)
class AttackCorpusTest {

    @Test
    fun sevenOfSevenAttacksHandledCorrectly() {
        val report = WalletLab().runAttackCorpus()

        report.results.forEach { r ->
            println("[corpus] ${r.label.padEnd(36)} -> ${r.actualCode.padEnd(20)} ${if (r.passed) "PASS" else "FAIL"}")
        }
        println("[corpus] ${report.summary}")

        assertEquals("every attack variant must be evaluated", 7, report.total)
        assertEquals("7 of 7 attacks handled correctly", 7, report.passedCount)
        assertTrue(report.allPassed)
    }

    @Test
    fun honestChainIsAcceptedAndTamperedChainsAreNot() {
        val report = WalletLab().runAttackCorpus()
        val honest = report.results.first()
        assertEquals("ACCEPT", honest.actualCode)
        // Everything after the control must refuse.
        report.results.drop(1).forEach { r ->
            assertTrue("${r.label} should have refused but returned ${r.actualCode}", r.actualCode != "ACCEPT")
        }
    }

    @Test
    fun truncatingTheChainIsCaughtByTheGapCheck() {
        val report = WalletLab().runAttackCorpus()
        val gap = report.results.first { it.label.contains("Delete the last 2") }
        assertEquals(TerminalVerifier.RefusalCode.REFUSE_GAP.name, gap.actualCode)
        // The detail names the exact discrepancy, verbatim.
        println("[corpus] gap detail: ${gap.detail}")
        assertTrue(gap.detail.contains("concealed"))
    }
}
