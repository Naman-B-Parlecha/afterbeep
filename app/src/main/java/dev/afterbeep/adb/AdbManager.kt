package dev.afterbeep.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Our own adb client identity (like a computer's adbkey). Paired once with the phone's Wireless
 * debugging; the pairing survives reboots because the key + cert are persisted here.
 */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(ctx.filesDir, "adb/key.pk8")
    private val certFile = File(ctx.filesDir, "adb/cert.der")
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            privateKey = pair.private
            val name = X500Name("CN=Afterbeep")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 30L * 365 * 86_400_000L), name, pair.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            certificate = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "Afterbeep"

    companion object {
        @Volatile private var instance: AdbManager? = null
        /** Drop the instance after a hung connection; the next get() builds a clean one (same persisted key). */
        fun reset() { synchronized(this) { instance = null } }
        fun get(ctx: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(ctx.applicationContext).also { instance = it } }
    }
}
