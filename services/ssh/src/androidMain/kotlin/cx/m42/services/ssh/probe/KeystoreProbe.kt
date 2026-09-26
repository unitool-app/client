package cx.m42.services.ssh.probe

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Step 0 of ssh-new/04 §2: what this device's Keystore can do, asked of the Keystore itself.
 *
 * It asserts nothing. Each question P1–P8 gets an [Answer] — yes, no, or not applicable — with the
 * platform's own words as evidence, and the table in 04 §2 is filled from what it printed on
 * `unitool35` and on a phone. The same answers later become facts of the self-test (06 §3), which
 * is why this is `androidMain` and not a test: a tester's phone runs it from Settings.
 *
 * Every key it makes is its own, under [prefix], and is deleted before [run] returns. The keys are
 * the published test vectors of RFC 8032 and RFC 7748, never anything of the person's.
 */
public class KeystoreProbe(private val context: Context) {

    public enum class Verdict { Yes, No, NotApplicable }

    public data class Answer(val code: String, val verdict: Verdict, val detail: String)

    private val keystore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private val prefix = "cx.m42.services.ssh.probe." + System.nanoTime() + "."
    private val answers = mutableListOf<Answer>()

    /** Auth-bound keys [run] leaves in place, for [afterLockRemoved]. */
    public val lockedAliases: MutableList<String> = mutableListOf()

    /**
     * P1–P6 and P8 always; P7 only when [screenLockSet] — a key bound to authentication cannot be
     * made on a device with nothing to authenticate with, and the caller is the one who knows.
     */
    public fun run(screenLockSet: Boolean): List<Answer> {
        try {
            facts()
            p1Ed25519Import()
            p1bEd25519Generate()
            p2X25519Import()
            p3RsaOaepLabel()
            p4RsaRaw()
            p5CertificateSignature()
            p6StrongBox()
            if (screenLockSet) p7WithoutPrompt() else say("P7", Verdict.NotApplicable, "no screen lock set")
        } finally {
            keystore.aliases().toList().filter { it.startsWith(prefix) }.forEach { runCatching { keystore.deleteEntry(it) } }
        }
        return answers.toList()
    }

    /**
     * What happens to auth-bound keys once the screen lock is gone (01 §5.7). Call after the lock
     * was removed, with the [lockedAliases] of the probe that ran under it.
     */
    public fun afterLockRemoved(aliases: List<String>): List<Answer> {
        val out = mutableListOf<Answer>()
        aliases.forEach { alias ->
            if (!keystore.containsAlias(alias)) {
                out += Answer("P7.invalidated", Verdict.Yes, "$alias → deleted by the system when the lock was removed")
                return@forEach
            }
            val outcome = runCatching {
                val key = keystore.getKey(alias, null)
                when (key) {
                    is PrivateKey -> Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
                    is SecretKey -> Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                    null -> error("key gone")
                    else -> error("unexpected ${key.javaClass.name}")
                }
            }
            out += Answer(
                "P7.invalidated",
                if (outcome.isFailure) Verdict.Yes else Verdict.No,
                "$alias → " + outcome.fold({ "init succeeded" }, { describe(it) }),
            )
            runCatching { keystore.deleteEntry(alias) }
        }
        if (aliases.isEmpty()) out += Answer("P7.invalidated", Verdict.NotApplicable, "no auth-bound probe keys were left")
        return out
    }

    /** The auth-bound keys, gone — on a device whose lock stays, where [afterLockRemoved] never runs. */
    public fun deleteLocked() {
        lockedAliases.forEach { runCatching { keystore.deleteEntry(it) } }
    }

    // ------------------------------------------------------------ facts

    private fun facts() {
        say(
            "device",
            Verdict.Yes,
            "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} product=${Build.PRODUCT} " +
                "keystoreFeatureVersion=${featureVersion(PackageManager.FEATURE_HARDWARE_KEYSTORE)} " +
                "strongBoxFeatureVersion=${featureVersion(PackageManager.FEATURE_STRONGBOX_KEYSTORE)}",
        )
    }

    private fun featureVersion(feature: String): String {
        val info = context.packageManager.systemAvailableFeatures.firstOrNull { it.name == feature }
        return info?.version?.toString() ?: "absent"
    }

    // ------------------------------------------------------------ P1: Ed25519

    private fun p1Ed25519Import() {
        val pkcs8 = ED25519_PKCS8_PREFIX + RFC8032_SEED
        val spki = ED25519_SPKI_PREFIX + RFC8032_PUBLIC
        val attempts = mutableListOf<String>()
        var imported: String? = null

        // A: the platform's own JCA, if it has an Ed25519 KeyFactory at all.
        val jcaKey = runCatching { KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }
        attempts += "A KeyFactory(Ed25519): " + jcaKey.fold({ "${it.javaClass.name} alg=${it.algorithm}" }, ::describe)
        val jcaCert = runCatching { parsedCertificate(spki, ED25519_OID) }
        attempts += "A CertificateFactory(Ed25519 SPKI): " +
            jcaCert.fold({ "publicKey.alg=${runCatching { it.publicKey.algorithm }.getOrElse(::describe)}" }, ::describe)
        if (jcaKey.isSuccess && jcaCert.isSuccess) {
            val alias = prefix + "ed25519.a"
            val r = runCatching { importSign(alias, jcaKey.getOrThrow(), jcaCert.getOrThrow()) }
            attempts += "A import: " + r.fold({ "ok" }, ::describe)
            if (r.isSuccess) imported = alias
        }

        // B–D: our own PrivateKey carrying PKCS#8, under the names the platform might map.
        for (name in listOf("Ed25519", "EdDSA", "EC", "XDH")) {
            if (imported != null) break
            val alias = prefix + "ed25519." + name.lowercase()
            val cert = HandMadeCertificate(spki, ED25519_OID, name)
            val r = runCatching { importSign(alias, RawPrivateKey(name, pkcs8), cert) }
            attempts += "own key alg=$name: " + r.fold({ "ok" }, ::describe)
            if (r.isSuccess) imported = alias
        }

        if (imported == null) {
            say("P1", Verdict.No, attempts.joinToString(" | "))
            return
        }
        val key = keystore.getKey(imported, null) as PrivateKey
        val signing = listOf("Ed25519", "EdDSA", "NONEwithEdDSA").map { algorithm ->
            algorithm to runCatching {
                Signature.getInstance(algorithm).run {
                    initSign(key)
                    update(ByteArray(0))
                    sign()
                }
            }
        }
        val good = signing.firstOrNull { it.second.getOrNull()?.contentEquals(RFC8032_SIGNATURE_EMPTY) == true }
        val detail = attempts.joinToString(" | ") + " || sign: " + signing.joinToString(" | ") { (a, r) ->
            "$a → " + r.fold({ if (it.contentEquals(RFC8032_SIGNATURE_EMPTY)) "RFC 8032 vector matches" else "WRONG signature ${it.size}B" }, ::describe)
        } + " || " + keyInfo(key)
        say("P1", if (good != null) Verdict.Yes else Verdict.No, detail)
    }

    private fun importSign(alias: String, key: PrivateKey, cert: X509Certificate) {
        keystore.setEntry(
            alias,
            KeyStore.PrivateKeyEntry(key, arrayOf(cert)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_NONE).build(),
        )
    }

    /** Not a P-question, but the answer to "can the phone make Ed25519 itself" (01 §4.4). */
    private fun p1bEd25519Generate() {
        val alias = prefix + "ed25519.generated"
        val r = runCatching {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .build(),
            )
            val pair = generator.generateKeyPair()
            val signature = Signature.getInstance("Ed25519").run { initSign(pair.private); update(byteArrayOf(1)); sign() }
            "public alg=${pair.public.algorithm} encoded=${pair.public.encoded?.size}B signature=${signature.size}B; " + keyInfo(pair.private)
        }
        say("P1.generate", if (r.isSuccess) Verdict.Yes else Verdict.No, r.fold({ it }, ::describe))
    }

    // ------------------------------------------------------------ P2: X25519

    private fun p2X25519Import() {
        val pkcs8 = X25519_PKCS8_PREFIX + RFC7748_ALICE_PRIVATE
        val attempts = mutableListOf<String>()
        var result: Pair<String, ByteArray>? = null
        for (name in listOf("XDH", "X25519")) {
            val alias = prefix + "x25519." + name.lowercase()
            val cert = HandMadeCertificate(X25519_SPKI_PREFIX + RFC7748_ALICE_PUBLIC, ED25519_OID, name)
            val imported = runCatching {
                keystore.setEntry(
                    alias,
                    KeyStore.PrivateKeyEntry(RawPrivateKey(name, pkcs8), arrayOf(cert)),
                    KeyProtection.Builder(KeyProperties.PURPOSE_AGREE_KEY).build(),
                )
            }
            attempts += "import alg=$name: " + imported.fold({ "ok" }, ::describe)
            if (imported.isFailure) continue
            val key = keystore.getKey(alias, null) as PrivateKey
            attempts += keyInfo(key)
            for (agreement in listOf("XDH", "X25519")) {
                for (peer in peerKeys()) {
                    val shared = runCatching {
                        KeyAgreement.getInstance(agreement, PROVIDER).run {
                            init(key)
                            doPhase(peer.second, true)
                            generateSecret()
                        }
                    }
                    attempts += "KeyAgreement($agreement) peer=${peer.first}: " + shared.fold(
                        { if (it.contentEquals(RFC7748_SHARED)) "RFC 7748 vector matches" else "WRONG secret ${it.size}B" },
                        ::describe,
                    )
                    if (shared.getOrNull()?.contentEquals(RFC7748_SHARED) == true) result = "$name/$agreement/${peer.first}" to shared.getOrThrow()
                    if (result != null) break
                }
                if (result != null) break
            }
            if (result != null) break
        }
        say("P2", if (result != null) Verdict.Yes else Verdict.No, attempts.joinToString(" | "))
    }

    private fun peerKeys(): List<Pair<String, PublicKey>> = buildList {
        val spki = X25519_SPKI_PREFIX + RFC7748_BOB_PUBLIC
        for (name in listOf("XDH", "X25519")) {
            runCatching { KeyFactory.getInstance(name).generatePublic(java.security.spec.X509EncodedKeySpec(spki)) }
                .onSuccess { add("KeyFactory($name)" to it) }
        }
        add("own(XDH)" to RawPublicKey("XDH", spki))
    }

    // ------------------------------------------------------------ P3, P4: RSA for age

    private fun rsaPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun p3RsaOaepLabel() {
        val pair = rsaPair()
        val label = "age-encryption.org/v1/ssh-rsa".encodeToByteArray()
        val message = ByteArray(16) { it.toByte() }
        val attempts = mutableListOf<String>()
        var ok = false
        for (mgf1 in listOf(MGF1ParameterSpec.SHA256, MGF1ParameterSpec.SHA1)) {
            val spec = OAEPParameterSpec("SHA-256", "MGF1", mgf1, PSource.PSpecified(label))
            val alias = prefix + "rsa.oaep." + mgf1.digestAlgorithm
            val protection = KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (Build.VERSION.SDK_INT >= 34) setMgf1Digests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1) }
                .build()
            val r = runCatching {
                keystore.setEntry(alias, KeyStore.PrivateKeyEntry(pair.private, arrayOf(signedCertificate(pair.public, pair.private))), protection)
                val ciphertext = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
                    init(Cipher.ENCRYPT_MODE, pair.public, spec)
                    doFinal(message)
                }
                val key = keystore.getKey(alias, null) as PrivateKey
                // No provider named: the key picks AndroidKeyStore itself. Naming it fails outright —
                // the provider registers the transformation under a workaround provider (probe 1).
                Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
                    init(Cipher.DECRYPT_MODE, key, spec)
                    doFinal(ciphertext)
                }
            }
            val matches = r.getOrNull()?.contentEquals(message) == true
            if (matches && mgf1 == MGF1ParameterSpec.SHA256) ok = true
            attempts += "MGF1-${mgf1.digestAlgorithm} label=age: " + r.fold({ if (matches) "decrypts" else "WRONG plaintext" }, ::describe)
        }
        say("P3", if (ok) Verdict.Yes else Verdict.No, attempts.joinToString(" | "))
    }

    private fun p4RsaRaw() {
        val pair = rsaPair()
        val alias = prefix + "rsa.raw"
        val r = runCatching {
            keystore.setEntry(
                alias,
                KeyStore.PrivateKeyEntry(pair.private, arrayOf(signedCertificate(pair.public, pair.private))),
                KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .build(),
            )
            // A number below n: a leading zero byte guarantees it, as OAEP's own EM does.
            val em = ByteArray(256).also { java.security.SecureRandom().nextBytes(it); it[0] = 0 }
            val ciphertext = Cipher.getInstance("RSA/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, pair.public); doFinal(em) }
            val key = keystore.getKey(alias, null) as PrivateKey
            val plain = Cipher.getInstance("RSA/ECB/NoPadding").run { init(Cipher.DECRYPT_MODE, key); doFinal(ciphertext) }
            val back = BigInteger(1, plain) == BigInteger(1, em)
            (if (back) "raw RSA decrypts; " else "WRONG plaintext; ") + keyInfo(key)
        }
        say("P4", if (r.getOrNull()?.startsWith("raw RSA decrypts") == true) Verdict.Yes else Verdict.No, r.fold({ it }, ::describe))
    }

    // ------------------------------------------------------------ P5: the certificate's signature

    private fun p5CertificateSignature() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val alias = prefix + "p256.junk-cert"
        val r = runCatching {
            val junk = parsedCertificate(pair.public.encoded, ECDSA_SHA256_OID, signature = ByteArray(72) { 0x5a })
            keystore.setEntry(
                alias,
                KeyStore.PrivateKeyEntry(pair.private, arrayOf(junk)),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build(),
            )
            val key = keystore.getKey(alias, null) as PrivateKey
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(byteArrayOf(1, 2, 3)); sign() }
            val verified = Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(byteArrayOf(1, 2, 3)); verify(signature) }
            "imported with a certificate whose signature is junk; signs=${verified}; " + keyInfo(key)
        }
        // "Yes" answers the question as asked — does the Keystore check the signature — so a
        // successful import with junk is a No.
        say("P5", if (r.isSuccess) Verdict.No else Verdict.Yes, r.fold({ it }, ::describe))
    }

    // ------------------------------------------------------------ P6: StrongBox

    private fun p6StrongBox() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            say("P6", Verdict.NotApplicable, "no FEATURE_STRONGBOX_KEYSTORE")
            return
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val alias = prefix + "p256.strongbox"
        val r = runCatching {
            keystore.setEntry(
                alias,
                KeyStore.PrivateKeyEntry(pair.private, arrayOf(signedCertificate(pair.public, pair.private))),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).setIsStrongBoxBacked(true).build(),
            )
            keyInfo(keystore.getKey(alias, null) as PrivateKey)
        }
        say("P6", if (r.isSuccess) Verdict.Yes else Verdict.No, r.fold({ it }, ::describe))
    }

    // ------------------------------------------------------------ P7: no prompt, no operation

    private fun p7WithoutPrompt() {
        val parts = mutableListOf<String>()

        // An imported P-256 bound to authentication, every use.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signAlias = LOCKED_PREFIX + System.nanoTime() + ".sign"
        lockedAliases += signAlias
        val imported = runCatching {
            keystore.setEntry(
                signAlias,
                KeyStore.PrivateKeyEntry(pair.private, arrayOf(signedCertificate(pair.public, pair.private))),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setUnlockedDeviceRequired(true)
                    .setInvalidatedByBiometricEnrollment(false)
                    .build(),
            )
        }
        parts += "import auth-bound P-256: " + imported.fold({ "ok" }, ::describe)
        if (imported.isSuccess) {
            val key = keystore.getKey(signAlias, null) as PrivateKey
            parts += keyInfo(key)
            val signature = Signature.getInstance("SHA256withECDSA")
            val init = runCatching { signature.initSign(key) }
            parts += "Signature.initSign: " + init.fold({ "ok" }, ::describe)
            if (init.isSuccess) {
                val update = runCatching { signature.update(byteArrayOf(1)) }
                parts += "update: " + update.fold({ "ok" }, ::describe)
                val sign = runCatching { signature.sign() }
                parts += "sign: " + sign.fold({ "SIGNED ${it.size}B" }, ::describe)
            }
        }

        // A generated AES-GCM wrap key, the level-2 envelope (01 §4.2).
        val wrapAlias = LOCKED_PREFIX + System.nanoTime() + ".wrap"
        lockedAliases += wrapAlias
        val made = runCatching {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
                init(
                    KeyGenParameterSpec.Builder(wrapAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                        .setUnlockedDeviceRequired(true)
                        .setInvalidatedByBiometricEnrollment(false)
                        .build(),
                )
            }.generateKey()
        }
        parts += "generate auth-bound AES-GCM: " + made.fold({ "ok" }, ::describe)
        made.getOrNull()?.let { key ->
            parts += secretKeyInfo(key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val init = runCatching { cipher.init(Cipher.ENCRYPT_MODE, key) }
            parts += "Cipher.init(ENCRYPT): " + init.fold({ "ok" }, ::describe)
            if (init.isSuccess) {
                parts += "doFinal: " + runCatching { cipher.doFinal(byteArrayOf(1, 2, 3)) }.fold({ "ENCRYPTED ${it.size}B" }, ::describe)
            }
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            parts += "Cipher.init(DECRYPT): " + runCatching {
                decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
            }.fold({ "ok" }, ::describe)
        }
        val refused = parts.none { "SIGNED" in it || "ENCRYPTED" in it }
        say("P7", if (refused) Verdict.Yes else Verdict.No, parts.joinToString(" | "))
    }

    // ------------------------------------------------------------ P8 comes with every key above

    /**
     * The key's own algorithm name first; for Curve25519 keys the platform reports `EdDSA`/`XDH`
     * and has a KeyFactory under neither on every version, so the other names are tried too.
     */
    private fun keyInfo(key: PrivateKey): String {
        val failures = mutableListOf<String>()
        for (name in listOf(key.algorithm, "EC", "XDH", "Ed25519", "X25519").distinct()) {
            val info = runCatching { KeyFactory.getInstance(name, PROVIDER).getKeySpec(key, KeyInfo::class.java) }
            if (info.isSuccess) return (if (name != key.algorithm) "(KeyFactory $name) " else "") + describeInfo(info.getOrThrow())
            failures += "$name: " + describe(info.exceptionOrNull()!!)
        }
        return "KeyInfo: " + failures.joinToString(" / ")
    }

    private fun secretKeyInfo(key: SecretKey): String = runCatching {
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        describeInfo(info)
    }.getOrElse { "KeyInfo: " + describe(it) }

    private fun describeInfo(info: KeyInfo): String {
        val level = if (Build.VERSION.SDK_INT >= 31) securityLevel(info.securityLevel) else "insideSecureHardware=${@Suppress("DEPRECATION") info.isInsideSecureHardware}"
        answers += Answer("P8", Verdict.Yes, "${info.keystoreAlias.removePrefix(prefix)}: level=$level")
        return "KeyInfo level=$level authRequired=${info.isUserAuthenticationRequired} " +
            "authEnforcedByHardware=${info.isUserAuthenticationRequirementEnforcedBySecureHardware} " +
            "timeout=${if (Build.VERSION.SDK_INT >= 30) info.userAuthenticationValidityDurationSeconds else "?"} " +
            "invalidatedByEnrollment=${info.isInvalidatedByBiometricEnrollment}"
    }

    private fun securityLevel(level: Int): String = when (level) {
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "unknown-secure"
        else -> "unknown($level)"
    }

    // ------------------------------------------------------------ plumbing

    private fun say(code: String, verdict: Verdict, detail: String) {
        answers += Answer(code, verdict, detail)
    }

    private fun describe(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(3).joinToString(" ← ") { "${it.javaClass.name}: ${it.message}" }
        return chain.replace('\n', ' ')
    }

    private fun parsedCertificate(spki: ByteArray, signatureOid: ByteArray, signature: ByteArray = ByteArray(64)): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(SelfSignedCertificate.der(spki, signatureOid, signature))) as X509Certificate

    /** A certificate the platform parses, signed for real, for the key types JCA knows. */
    private fun signedCertificate(public: PublicKey, private: PrivateKey): X509Certificate {
        val (oid, algorithm) = when (public.algorithm) {
            "RSA" -> RSA_SHA256_OID to "SHA256withRSA"
            else -> ECDSA_SHA256_OID to "SHA256withECDSA"
        }
        val tbs = SelfSignedCertificate.tbs(public.encoded, oid)
        val signature = Signature.getInstance(algorithm).run { initSign(private); update(tbs); sign() }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(SelfSignedCertificate.wrap(tbs, oid, signature))) as X509Certificate
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val LOCKED_PREFIX = "cx.m42.services.ssh.probe.locked."

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // RFC 8032 §7.1, test 1.
        val RFC8032_SEED = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val RFC8032_PUBLIC = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val RFC8032_SIGNATURE_EMPTY = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )

        // RFC 7748 §6.1.
        val RFC7748_ALICE_PRIVATE = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val RFC7748_ALICE_PUBLIC = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val RFC7748_BOB_PUBLIC = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val RFC7748_SHARED = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        // RFC 8410: PKCS#8 and SubjectPublicKeyInfo for the two curves, up to the key bytes.
        val ED25519_PKCS8_PREFIX = hex("302e020100300506032b657004220420")
        val ED25519_SPKI_PREFIX = hex("302a300506032b6570032100")
        val X25519_PKCS8_PREFIX = hex("302e020100300506032b656e04220420")
        val X25519_SPKI_PREFIX = hex("302a300506032b656e032100")

        // AlgorithmIdentifier contents: the OID, and NULL parameters where the algorithm has them.
        // A certificate for an X25519 key names Ed25519 as its signature algorithm too: it needs
        // *some* algorithm, and X25519 cannot sign.
        val ED25519_OID = hex("06032b6570")
        val ECDSA_SHA256_OID = hex("06082a8648ce3d040302")
        val RSA_SHA256_OID = hex("06092a864886f70d01010b0500")
    }
}

/** PKCS#8 in a `PrivateKey`, for algorithms the platform's JCA has no `KeyFactory` for. */
internal class RawPrivateKey(private val name: String, private val pkcs8: ByteArray) : PrivateKey {
    override fun getAlgorithm(): String = name
    override fun getFormat(): String = "PKCS#8"
    override fun getEncoded(): ByteArray = pkcs8.copyOf()
}

/** A SubjectPublicKeyInfo in a `PublicKey`, likewise. */
internal class RawPublicKey(private val name: String, private val spki: ByteArray) : PublicKey {
    override fun getAlgorithm(): String = name
    override fun getFormat(): String = "X.509"
    override fun getEncoded(): ByteArray = spki.copyOf()
}
