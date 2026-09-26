package cx.m42.services.ssh.keyring

import android.annotation.TargetApi
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.SshSig
import cx.m42.services.ssh.crypto.EcdsaDer
import cx.m42.services.ssh.crypto.Hash
import cx.m42.services.ssh.crypto.Sha512
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.probe.HandMadeCertificate
import cx.m42.services.ssh.probe.RawPrivateKey
import cx.m42.services.ssh.wire.WireReader
import cx.m42.services.ssh.wire.wire
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.UsePolicy
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android's Keystore as the keyring's chip (01 §4, §5; step 0's answers in 04 §2).
 *
 * Level 1 imports the key itself: RSA and ECDSA through the JCA, Ed25519 and its X25519 twin as a
 * `PrivateKey` named `XDH` carrying PKCS#8 with a hand-made certificate — the one way step 0 found
 * (P1, P2). Level 2 seals the key file with an AES-GCM key of its own. Every key is bound to
 * authentication with a zero timeout for "every use" (01 §5.1), needs an unlocked device, and
 * survives a new fingerprint (D144). Each operation puts the platform's `BiometricPrompt` up with a
 * `CryptoObject` for exactly that `Signature` or `Cipher` — except the X25519 twin: no
 * `CryptoObject` can hold a `KeyAgreement`, so that key opens for [AGREE_WINDOW] seconds after the
 * same prompt (impl, 01 §5.1).
 *
 * API 30+ only: "every use" and the choice of authenticators need `setUserAuthenticationParameters`,
 * so an older phone gets no chip at all ([android] passes null). Where the Keystore is software —
 * an emulator, step 0's P8 — [device] offers level 3 only and the keyring never calls the rest.
 */
@TargetApi(30)
internal class KeystoreBackend(
    context: Context,
    private val activity: () -> Activity?,
    private val texts: (UseReason) -> PromptText,
) : ChipBackend {

    private val app = context.applicationContext
    private val keystore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private val sdk = Build.VERSION.SDK_INT
    private val hasStrongBox = sdk >= 28 && app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** Where a Keystore key really lives here, learned once from a throwaway key (P8). */
    private val hardware: String by lazy { probeLevel() }

    override val device: KeyringDevice
        get() {
            val lock = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
            val chip = sdk >= 30 && hardware != "software"
            return KeyringDevice(
                platform = Platform.Android,
                sdk = sdk,
                strongBox = hasStrongBox,
                tee = hardware == "tee" || hardware == "strongbox",
                curve25519InKeystore = chip && sdk >= 33,
                screenLockSet = lock,
                levels = if (chip) setOf(1, 2) else setOf(3),
            )
        }

    /** "strongbox", "tee", "software" — for the device facts and for the vault's own finding. */
    val securityLevel: String get() = hardware

    override fun placement(key: SshPublicKey, generated: Boolean): KeyStorage? {
        val d = device
        if (1 !in d.levels) return null
        return when (val kind = key.kind) {
            is SshPublicKey.Kind.Rsa -> KeyStorage.Tee
            is SshPublicKey.Kind.Ecdsa -> if (kind.curve == SshPublicKey.Curve.P256 && hasStrongBox) KeyStorage.StrongBox else KeyStorage.Tee
            is SshPublicKey.Kind.Ed25519 -> if (d.curve25519InKeystore) KeyStorage.Tee else KeyStorage.SealedByChip(if (hasStrongBox) Chip.StrongBox else Chip.Tee)
            SshPublicKey.Kind.Other -> null
        }
    }

    // ------------------------------------------------------------ taking keys in

    override suspend fun store(alias: String, storage: KeyStorage, key: PrivateKey, file: ByteArray, policy: UsePolicy, reason: UseReason): ChipResult<String?> =
        withContext(Dispatchers.IO) {
            if (!device.screenLockSet) return@withContext ChipResult.NoScreenLock
            try {
                if (storage.level == 1) {
                    importResident(alias, key, storage == KeyStorage.StrongBox, policy)
                    ChipResult.Ok(null)
                } else {
                    val wrap = makeWrapKey(alias, storage is KeyStorage.SealedByChip && storage.under == Chip.StrongBox, policy)
                    val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, wrap) }
                    when (val shown = prompt(reason, BiometricPrompt.CryptoObject(cipher))) {
                        is ChipResult.Ok -> ChipResult.Ok("v1:" + Base64.encode(cipher.iv + cipher.doFinal(file)))
                        else -> {
                            delete(alias)
                            shown.fail()
                        }
                    }
                }
            } catch (e: Exception) {
                delete(alias)
                failure(e)
            }
        }

    /** [window]: the least number of seconds a confirmation lasts — 0 is "this one operation". */
    private fun protection(purposes: Int, policy: UsePolicy, strongBox: Boolean, window: Int = 0): KeyProtection.Builder =
        KeyProtection.Builder(purposes)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(maxOf(timeout(policy), window), KEY_AUTH)
            .setUnlockedDeviceRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }

    private fun timeout(policy: UsePolicy): Int = when (policy) {
        UsePolicy.EveryUse -> 0
        is UsePolicy.Session -> policy.seconds.coerceAtLeast(1)
    }

    private fun importResident(alias: String, key: PrivateKey, strongBox: Boolean, policy: UsePolicy) {
        val public = key.publicKey
        when (val kind = public.kind) {
            is SshPublicKey.Kind.Ed25519 -> {
                key.ed25519Seed().use { seed ->
                    val signing = RawPrivateKey("XDH", ED25519_PKCS8 + seed.bytes)
                    keystore.setEntry(
                        "$alias.sign",
                        KeyStore.PrivateKeyEntry(signing, arrayOf(HandMadeCertificate(ED25519_SPKI + kind.point, ED25519_OID, "XDH"))),
                        protection(KeyProperties.PURPOSE_SIGN, policy, false).setDigests(KeyProperties.DIGEST_NONE).build(),
                    )
                    val h = Sha512.digest(seed.bytes)
                    try {
                        val scalar = h.copyOf(32)
                        val agreeing = RawPrivateKey("XDH", X25519_PKCS8 + scalar)
                        scalar.fill(0)
                        val twin = cx.m42.services.ssh.crypto.Curve25519.edwardsToMontgomery(kind.point)
                        keystore.setEntry(
                            "$alias.agree",
                            KeyStore.PrivateKeyEntry(agreeing, arrayOf(HandMadeCertificate(X25519_SPKI + twin, ED25519_OID, "XDH"))),
                            protection(KeyProperties.PURPOSE_AGREE_KEY, policy, false, window = AGREE_WINDOW).build(),
                        )
                    } finally {
                        h.fill(0)
                    }
                }
            }
            is SshPublicKey.Kind.Rsa -> {
                val fields = WireReader(key.fields.bytes)
                val n = BigInteger(fields.mpint()!!)
                val e = BigInteger(fields.mpint()!!)
                val d = BigInteger(fields.mpint()!!)
                val iqmp = BigInteger(fields.mpint()!!)
                val p = BigInteger(fields.mpint()!!)
                val q = BigInteger(fields.mpint()!!)
                // BigInteger cannot be wiped (01 §4.5): these live until the collector takes them.
                val spec = RSAPrivateCrtKeySpec(n, e, d, p, q, d.mod(p - BigInteger.ONE), d.mod(q - BigInteger.ONE), iqmp)
                val private = KeyFactory.getInstance("RSA").generatePrivate(spec)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(n, e))
                val certificate = HandMadeCertificate(publicKey.encoded, RSA_SHA256_OID, "RSA")
                keystore.setEntry(
                    "$alias.sign",
                    KeyStore.PrivateKeyEntry(private, arrayOf(certificate)),
                    protection(KeyProperties.PURPOSE_SIGN, policy, false)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build(),
                )
                // "Raw" RSA only under its own alias, for age's OAEP with a label the Keystore refuses (P3, P4, D144).
                keystore.setEntry(
                    "$alias.decrypt",
                    KeyStore.PrivateKeyEntry(private, arrayOf(certificate)),
                    protection(KeyProperties.PURPOSE_DECRYPT, policy, false)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .build(),
                )
            }
            is SshPublicKey.Kind.Ecdsa -> {
                val params = java.security.AlgorithmParameters.getInstance("EC").run {
                    init(ECGenParameterSpec(curveName(kind.curve)))
                    getParameterSpec(java.security.spec.ECParameterSpec::class.java)
                }
                val private = key.ecdsaPrivateScalar().use { KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, it.bytes), params)) }
                val field = kind.curve.fieldBytes
                val x = BigInteger(1, kind.point.copyOfRange(1, 1 + field))
                val y = BigInteger(1, kind.point.copyOfRange(1 + field, kind.point.size))
                val publicKey = KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), params))
                keystore.setEntry(
                    "$alias.sign",
                    KeyStore.PrivateKeyEntry(private, arrayOf(HandMadeCertificate(publicKey.encoded, ECDSA_SHA256_OID, "EC"))),
                    protection(KeyProperties.PURPOSE_SIGN, policy, strongBox)
                        .setDigests(digest(kind.curve))
                        .build(),
                )
            }
            SshPublicKey.Kind.Other -> error("not a key the keyring takes")
        }
    }

    private fun makeWrapKey(alias: String, strongBox: Boolean, policy: UsePolicy): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder("$alias.wrap", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(timeout(policy), KEY_AUTH)
                    .setUnlockedDeviceRequired(true)
                    .setInvalidatedByBiometricEnrollment(false)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build(),
            )
        }.generateKey()

    override suspend fun generate(alias: String, kind: GeneratedKind, policy: UsePolicy, reason: UseReason): ChipResult<Pair<SshPublicKey, KeyStorage>> =
        withContext(Dispatchers.IO) {
            if (kind != GeneratedKind.EcdsaP256) return@withContext ChipResult.Failed("unsupported")
            if (!device.screenLockSet) return@withContext ChipResult.NoScreenLock
            val attempts = if (hasStrongBox) listOf(true, false) else listOf(false)
            var last: Exception? = null
            for (strongBox in attempts) {
                try {
                    val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                        initialize(
                            KeyGenParameterSpec.Builder("$alias.sign", KeyProperties.PURPOSE_SIGN)
                                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .setUserAuthenticationRequired(true)
                                .setUserAuthenticationParameters(timeout(policy), KEY_AUTH)
                                .setUnlockedDeviceRequired(true)
                                .setInvalidatedByBiometricEnrollment(false)
                                .setAttestationChallenge(alias.encodeToByteArray())
                                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                                .build(),
                        )
                    }.generateKeyPair()
                    val public = pair.public as ECPublicKey
                    val point = byteArrayOf(4) + fixed(public.w.affineX, 32) + fixed(public.w.affineY, 32)
                    val key = SshPublicKey.fromBlob(wire { string("ecdsa-sha2-nistp256"); string("nistp256"); string(point) })!!
                    return@withContext ChipResult.Ok(key to if (strongBox) KeyStorage.StrongBox else KeyStorage.Tee)
                } catch (e: Exception) {
                    last = e
                    delete(alias)
                }
            }
            failure(last ?: IllegalStateException("no key"))
        }

    // ------------------------------------------------------------ using them

    override suspend fun unseal(alias: String, sealed: String, reason: UseReason): ChipResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val wrap = keystore.getKey("$alias.wrap", null) as? SecretKey ?: return@withContext ChipResult.Unusable
            val body = Base64.decode(sealed.removePrefix("v1:"))
            val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, body, 0, 12)) }
            when (val shown = prompt(reason, BiometricPrompt.CryptoObject(cipher))) {
                is ChipResult.Ok -> ChipResult.Ok(cipher.doFinal(body, 12, body.size - 12))
                else -> shown.fail()
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    override suspend fun sign(alias: String, key: SshPublicKey, data: ByteArray, rsaHash: Hash, reason: UseReason): ChipResult<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val private = keystore.getKey("$alias.sign", null) as? java.security.PrivateKey ?: return@withContext ChipResult.Unusable
                val algorithm = when (val kind = key.kind) {
                    is SshPublicKey.Kind.Ed25519 -> "Ed25519"
                    is SshPublicKey.Kind.Rsa -> if (rsaHash == Hash.SHA256) "SHA256withRSA" else "SHA512withRSA"
                    is SshPublicKey.Kind.Ecdsa -> when (kind.curve) {
                        SshPublicKey.Curve.P256 -> "SHA256withECDSA"
                        SshPublicKey.Curve.P384 -> "SHA384withECDSA"
                        SshPublicKey.Curve.P521 -> "SHA512withECDSA"
                    }
                    SshPublicKey.Kind.Other -> return@withContext ChipResult.Failed("type")
                }
                val signature = Signature.getInstance(algorithm).apply { initSign(private) }
                when (val shown = prompt(reason, BiometricPrompt.CryptoObject(signature))) {
                    is ChipResult.Ok -> {
                        signature.update(data)
                        val raw = signature.sign()
                        ChipResult.Ok(
                            when (key.kind) {
                                is SshPublicKey.Kind.Ecdsa -> {
                                    val (r, s) = EcdsaDer.decode(raw) ?: return@withContext ChipResult.Failed("der")
                                    wire { string(key.type); string(cx.m42.services.ssh.wire.WireWriter().mpint(r).mpint(s).toByteArray()) }
                                }
                                is SshPublicKey.Kind.Rsa -> wire { string(if (rsaHash == Hash.SHA256) SshSig.RSA_SHA256 else SshSig.RSA_SHA512); string(raw) }
                                else -> wire { string(SshPublicKey.ED25519); string(raw) }
                            },
                        )
                    }
                    else -> shown.fail()
                }
            } catch (e: Exception) {
                failure(e)
            }
        }

    override suspend fun agree(alias: String, peer: ByteArray, reason: UseReason): ChipResult<ByteArray> = withContext(Dispatchers.IO) {
        if (sdk < 33) return@withContext ChipResult.Failed("api")
        try {
            val private = keystore.getKey("$alias.agree", null) as? java.security.PrivateKey ?: return@withContext ChipResult.Unusable
            val peerKey = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X25519_SPKI + peer))
            // The confirmation first, then the operation inside its window: the Keystore checks at init.
            when (val shown = prompt(reason, null)) {
                is ChipResult.Ok -> {
                    val agreement = KeyAgreement.getInstance("XDH", PROVIDER).apply { init(private) }
                    agreement.doPhase(peerKey, true)
                    ChipResult.Ok(agreement.generateSecret())
                }
                else -> shown.fail()
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    override suspend fun rsaDecrypt(alias: String, ciphertext: ByteArray, reason: UseReason): ChipResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val private = keystore.getKey("$alias.decrypt", null) as? java.security.PrivateKey ?: return@withContext ChipResult.Unusable
            val cipher = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, private) }
            when (val shown = prompt(reason, BiometricPrompt.CryptoObject(cipher))) {
                is ChipResult.Ok -> ChipResult.Ok(cipher.doFinal(ciphertext))
                else -> shown.fail()
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    override fun exists(alias: String): Boolean = runCatching {
        SUFFIXES.any { keystore.containsAlias(alias + it) }
    }.getOrDefault(false)

    override fun delete(alias: String) {
        SUFFIXES.forEach { runCatching { keystore.deleteEntry(alias + it) } }
    }

    // ------------------------------------------------------------ the prompt

    /**
     * The platform's `BiometricPrompt` with [crypto] as the one operation it authorises (01 §5.1);
     * without one, the confirmation opens a key's window instead (the X25519 twin). STRONG
     * biometrics or the screen lock.
     */
    private suspend fun prompt(reason: UseReason, crypto: BiometricPrompt.CryptoObject?): ChipResult<Unit> {
        val text = texts(reason)
        val context: Context = activity() ?: app
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                val prompt = BiometricPrompt.Builder(context)
                    .setTitle(text.title)
                    .apply { text.subtitle?.let(::setSubtitle); text.description?.let(::setDescription) }
                    .setAllowedAuthenticators(PROMPT_AUTH)
                    .setConfirmationRequired(false)
                    .build()
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(ChipResult.Ok(Unit))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> ChipResult.Cancelled
                                BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> ChipResult.NoScreenLock
                                else -> ChipResult.Failed("prompt-$errorCode", errString.toString())
                            },
                        )
                    }
                }
                if (crypto != null) {
                    prompt.authenticate(crypto, cancel, context.mainExecutor, callback)
                } else {
                    prompt.authenticate(cancel, context.mainExecutor, callback)
                }
            }
        }
    }

    private fun failure(e: Exception): ChipResult<Nothing> = when {
        e is KeyPermanentlyInvalidatedException -> ChipResult.Unusable
        e.message.orEmpty().contains("Secure lock screen", ignoreCase = true) -> ChipResult.NoScreenLock
        else -> ChipResult.Failed(e::class.simpleName ?: "keystore", e.message?.take(200))
    }

    private fun <T> ChipResult<Unit>.fail(): ChipResult<T> = when (this) {
        is ChipResult.Ok -> ChipResult.Failed("state")
        ChipResult.Cancelled -> ChipResult.Cancelled
        ChipResult.Unusable -> ChipResult.Unusable
        ChipResult.NoScreenLock -> ChipResult.NoScreenLock
        ChipResult.Busy -> ChipResult.Busy
        is ChipResult.Failed -> this
    }

    private fun probeLevel(): String = runCatching {
        val alias = "cx.m42.services.ssh.level." + System.nanoTime()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        try {
            val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (sdk >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "tee"
                    else -> "software"
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) "tee" else "software"
            }
        } finally {
            runCatching { keystore.deleteEntry(alias) }
        }
    }.getOrDefault("software")

    private fun curveName(curve: SshPublicKey.Curve) = when (curve) {
        SshPublicKey.Curve.P256 -> "secp256r1"
        SshPublicKey.Curve.P384 -> "secp384r1"
        SshPublicKey.Curve.P521 -> "secp521r1"
    }

    private fun digest(curve: SshPublicKey.Curve) = when (curve) {
        SshPublicKey.Curve.P256 -> KeyProperties.DIGEST_SHA256
        SshPublicKey.Curve.P384 -> KeyProperties.DIGEST_SHA384
        SshPublicKey.Curve.P521 -> KeyProperties.DIGEST_SHA512
    }

    private fun fixed(v: BigInteger, size: Int): ByteArray {
        val b = v.toByteArray()
        return if (b.size >= size) b.copyOfRange(b.size - size, b.size) else ByteArray(size - b.size) + b
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        /** Who may confirm, as a key records it (`KeyProperties.AUTH_*`)… */
        const val KEY_AUTH = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

        /** …and as the prompt asks for it (`BiometricManager.Authenticators`) — two sets of numbers for one rule. */
        const val PROMPT_AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /** Seconds the X25519 twin stays open after its prompt: long enough for every stanza of one file. */
        const val AGREE_WINDOW = 5
        val SUFFIXES = listOf(".sign", ".agree", ".decrypt", ".wrap")

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ED25519_PKCS8 = hex("302e020100300506032b657004220420")
        val ED25519_SPKI = hex("302a300506032b6570032100")
        val X25519_PKCS8 = hex("302e020100300506032b656e04220420")
        val X25519_SPKI = hex("302a300506032b656e032100")
        val ED25519_OID = hex("06032b6570")
        val ECDSA_SHA256_OID = hex("06082a8648ce3d040302")
        val RSA_SHA256_OID = hex("06092a864886f70d01010b0500")
    }
}
