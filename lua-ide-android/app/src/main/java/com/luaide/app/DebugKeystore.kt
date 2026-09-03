package com.luaide.app

import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Generates (once) and reuses a self-signed debug signing identity, entirely
 * on-device. This is the piece that lets "Preview APK" work without ever
 * shelling out to `keytool`/`apksigner` — both of which don't exist as
 * on-device binaries here. Uses SpongyCastle specifically (not upstream
 * Bouncy Castle) because Android ships its own stripped BC copy on the boot
 * classpath under the same package names, which collides with a bundled BC
 * jar; SpongyCastle exists precisely to sidestep that with a renamed package.
 */
object DebugKeystore {

    data class SigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

    private const val KEYSTORE_FILE = "lua_ide_debug.keystore"
    private const val KEYSTORE_PASSWORD = "lua-ide-debug" // self-signed, local-only preview signing — not a distribution key
    private const val ALIAS = "lua-ide-debug"

    fun getOrCreate(context: android.content.Context): SigningIdentity {
        val file = File(context.filesDir, KEYSTORE_FILE)
        if (Security.getProvider("SC") == null) {
            Security.addProvider(org.spongycastle.jce.provider.BouncyCastleProvider())
        }
        val ks = KeyStore.getInstance("BKS", "SC")

        if (file.exists()) {
            file.inputStream().use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
            val key = ks.getKey(ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            return SigningIdentity(key, cert)
        }

        // First run: generate a real RSA-2048 keypair and a real self-signed cert.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = Date()
        val expiry = Date(now.time + 1000L * 60 * 60 * 24 * 365 * 30) // 30 years, standard debug-cert practice
        val subject = X500Name("CN=Lua IDE Debug, O=Lua IDE, C=IN")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now.time), now, expiry, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        ks.load(null, KEYSTORE_PASSWORD.toCharArray())
        ks.setKeyEntry(ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }

        return SigningIdentity(keyPair.private, cert)
    }
}
