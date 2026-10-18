package lk.sliit.syntpay.shared

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature

/**
 * A batch of single-use hardware keys, SYNTPAY_PROJECT.md section 3.
 *
 * Each key is generated in the Android Keystore with setMaxUsageCount(1), so it
 * physically cannot sign twice. That primitive is why minSdk is 31 and not
 * negotiable: setMaxUsageCount does not exist below Android 12.
 *
 * One line explains the whole mechanism: you can edit your spending history,
 * but you cannot un-burn a key.
 *
 * THIS FILE IS SHARED. The wallet copy is canonical (section 2).
 */
object KeyBatch {

    const val BATCH_SIZE = 20
    private const val CURVE = "secp256r1"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val PROVIDER = "AndroidKeyStore"

    fun alias(batchId: String, index: Int): String = "syntpay.batch.$batchId.$index"

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun hasStrongBox(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** What backs the keys on this handset. Reported verbatim on the Status screen. */
    fun securityLevel(context: Context): String =
        if (hasStrongBox(context)) "StrongBox HSM (hardware)" else "Trusted Execution Environment (TEE)"

    /**
     * Generate [BATCH_SIZE] single-use keys. StrongBox is requested first and we
     * fall back to the TEE if the handset refuses, recording which was used
     * rather than claiming the stronger one. Returns the X.509 public keys in
     * index order.
     */
    fun generate(context: Context, batchId: String): BatchGeneration {
        val store = keyStore()
        val publicKeys = ArrayList<ByteArray>(BATCH_SIZE)
        var strongBoxUsed = hasStrongBox(context)

        for (index in 0 until BATCH_SIZE) {
            val keyAlias = alias(batchId, index)
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)

            var publicKey: PublicKey? = null
            if (strongBoxUsed) {
                publicKey = try {
                    generateOne(keyAlias, strongBox = true)
                } catch (e: StrongBoxUnavailableException) {
                    // Honest fallback: the handset advertised StrongBox but refused
                    // this spec. Downgrade the whole batch so the report cannot
                    // claim a mix.
                    strongBoxUsed = false
                    null
                }
            }
            if (publicKey == null) publicKey = generateOne(keyAlias, strongBox = false)
            publicKeys.add(publicKey.encoded)
        }

        return BatchGeneration(
            batchId = batchId,
            publicKeysX509 = publicKeys,
            keyRoot = merkleRoot(publicKeys),
            strongBoxBacked = strongBoxUsed,
        )
    }

    private fun generateOne(keyAlias: String, strongBox: Boolean): PublicKey {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // The primitive the whole project rests on.
            .setMaxUsageCount(1)
            .setIsStrongBoxBacked(strongBox)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }

    /** Sign with the key at [index]. Throws once that key has been burnt. */
    fun sign(batchId: String, index: Int, data: ByteArray): ByteArray {
        val store = keyStore()
        val entry = store.getEntry(alias(batchId, index), null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("no key at index $index for batch $batchId")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(data)
            sign()
        }
    }

    fun publicKeyX509(batchId: String, index: Int): ByteArray? =
        keyStore().getCertificate(alias(batchId, index))?.publicKey?.encoded

    fun exists(batchId: String, index: Int): Boolean = keyStore().containsAlias(alias(batchId, index))

    fun deleteBatch(batchId: String) {
        val store = keyStore()
        for (index in 0 until BATCH_SIZE) {
            val keyAlias = alias(batchId, index)
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        }
    }

    // ---- Merkle tree over the batch public keys (section 3 step 6) ----

    fun leafHash(publicKeyX509: ByteArray): ByteArray = CanonicalCbor.sha256(publicKeyX509)

    /**
     * Merkle root over the batch public keys. A level with an odd node count
     * promotes the last node by pairing it with itself.
     */
    fun merkleRoot(publicKeysX509: List<ByteArray>): ByteArray {
        require(publicKeysX509.isNotEmpty()) { "cannot build a Merkle root over an empty batch" }
        var level = publicKeysX509.map { leafHash(it) }
        while (level.size > 1) {
            val next = ArrayList<ByteArray>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else level[i]
                next.add(CanonicalCbor.sha256(left + right))
                i += 2
            }
            level = next
        }
        return level[0]
    }

    /** Sibling hashes from leaf to root, for the key at [index]. */
    fun merkleProof(publicKeysX509: List<ByteArray>, index: Int): List<ByteArray> {
        require(index in publicKeysX509.indices) { "index $index outside batch" }
        val proof = ArrayList<ByteArray>()
        var position = index
        var level = publicKeysX509.map { leafHash(it) }
        while (level.size > 1) {
            val siblingIndex = if (position % 2 == 0) position + 1 else position - 1
            proof.add(if (siblingIndex < level.size) level[siblingIndex] else level[position])
            val next = ArrayList<ByteArray>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else level[i]
                next.add(CanonicalCbor.sha256(left + right))
                i += 2
            }
            level = next
            position /= 2
        }
        return proof
    }

    /** Recompute the root from a leaf and its proof and compare against [root]. */
    fun verifyMerkleProof(
        publicKeyX509: ByteArray,
        index: Int,
        proof: List<ByteArray>,
        root: ByteArray,
    ): Boolean {
        var computed = leafHash(publicKeyX509)
        var position = index
        for (sibling in proof) {
            computed = if (position % 2 == 0) {
                CanonicalCbor.sha256(computed + sibling)
            } else {
                CanonicalCbor.sha256(sibling + computed)
            }
            position /= 2
        }
        return computed.contentEquals(root)
    }

    data class BatchGeneration(
        val batchId: String,
        val publicKeysX509: List<ByteArray>,
        val keyRoot: ByteArray,
        val strongBoxBacked: Boolean,
    )
}
