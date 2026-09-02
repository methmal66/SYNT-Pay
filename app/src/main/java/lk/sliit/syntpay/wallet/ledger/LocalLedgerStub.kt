package lk.sliit.syntpay.wallet.ledger

import android.content.Context
import lk.sliit.syntpay.shared.AllowanceVoucher
import lk.sliit.syntpay.shared.KeyBatch
import lk.sliit.syntpay.shared.hexToBytes
import lk.sliit.syntpay.shared.toHex
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * HONEST LIMITATION, SYNTPAY_PROJECT.md section 11.
 *
 * THE LEDGER IS STUBBED. This object signs allowance vouchers with a software
 * key held on the very device that then spends them, which proves NOTHING
 * cryptographically: the "issuer" and the spender are the same party. A real
 * deployment replaces this with the gateway and the Fabric chaincode, where the
 * signing key lives somewhere the customer's phone cannot reach.
 *
 * It is kept deliberately visible rather than hidden behind an interface,
 * because presenting it as working would be dishonest. Everything downstream of
 * it -- key generation, the spend chain, the gap check, the verifier -- is real.
 */
class LocalLedgerStub(context: Context) {

    private val prefs = context.getSharedPreferences("syntpay.ledger.stub", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PRIVATE = "ledgerPrivateKeyPkcs8"
        private const val KEY_PUBLIC = "ledgerPublicKeyX509"
        const val DEMO_ALLOWANCE_MINOR = 500_000L // LKR 5,000.00, section 7
        const val DEMO_ISSUED_AT_BLOCK = 94_000L
        const val DEMO_EXPIRY_BLOCK = 120_000L
        const val POLICY_VERSION = 1
    }

    private fun ensureKeyPair(): Pair<ByteArray, ByteArray> {
        val storedPrivate = prefs.getString(KEY_PRIVATE, null)
        val storedPublic = prefs.getString(KEY_PUBLIC, null)
        if (storedPrivate != null && storedPublic != null) {
            return hexToBytes(storedPrivate) to hexToBytes(storedPublic)
        }
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        val priv = pair.private.encoded
        val pub = pair.public.encoded
        prefs.edit()
            .putString(KEY_PRIVATE, priv.toHex())
            .putString(KEY_PUBLIC, pub.toHex())
            .apply()
        return priv to pub
    }

    fun publicKeyX509(): ByteArray = ensureKeyPair().second

    /** Issue and sign a voucher. In deployment this happens off-device. */
    fun issueVoucher(walletId: String, batchId: String, keyRoot: ByteArray): SignedVoucher {
        val voucher = AllowanceVoucher(
            batchId = batchId,
            keyRoot = keyRoot,
            walletId = walletId,
            allowanceMinor = DEMO_ALLOWANCE_MINOR,
            expiryBlock = DEMO_EXPIRY_BLOCK,
            issuedAtBlock = DEMO_ISSUED_AT_BLOCK,
            policyVersion = POLICY_VERSION,
        )
        val (privateBytes, publicBytes) = ensureKeyPair()
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val signature = Signature.getInstance(KeyBatch.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(voucher.canonicalEncoding())
            sign()
        }
        return SignedVoucher(voucher, signature, publicBytes)
    }

    data class SignedVoucher(
        val voucher: AllowanceVoucher,
        val ledgerSignature: ByteArray,
        val ledgerPublicKeyX509: ByteArray,
    )
}
