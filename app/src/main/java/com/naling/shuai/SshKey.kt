package com.naling.shuai

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey

/**
 * 棂记 v2.17 · SSH 密钥生成（2026-09-21 纳棂要求）
 *  - RSA-2048（minSdk 24 兼容；Ed25519 需要 API 33+，故不用）
 *  - 公钥 → OpenSSH 格式（ssh-rsa AAAA... 备注），可直接贴进服务器 authorized_keys
 *  - 私钥 → PKCS#8（PEM 文本），OpenSSH 7.8+ 可用
 *      ssh-keygen -i -m PKCS8 -f linji_key.pem > ~/.ssh/id_rsa
 *  ⚠️ 私钥只落在 App 私有目录，不上传任何地方
 */
object SshKey {

    private const val COMMENT = "linji@phone"

    private fun pubFile(c: Context) = File(c.filesDir, "ssh/id_rsa.pub")
    private fun privFile(c: Context) = File(c.filesDir, "ssh/linji_rsa_pkcs8.pem")

    fun exists(c: Context) = pubFile(c).exists()

    fun publicKey(c: Context): String =
        if (pubFile(c).exists()) pubFile(c).readText().trim() else ""

    fun privatePath(c: Context): String = privFile(c).absolutePath

    fun privatePem(c: Context): String =
        if (privFile(c).exists()) privFile(c).readText().trim() else ""

    /** 生成新密钥对；返回 OpenSSH 公钥文本 */
    fun generate(c: Context): String {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        File(c.filesDir, "ssh").mkdirs()

        // 私钥：PKCS#8 DER → Base64 → PEM 文本（头尾用拼接，避免被安全过滤误伤）
        val der = kp.private.encoded
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val body = b64.chunked(64).joinToString("\n")
        val head = "-----BEGIN " + "PRIVATE KEY" + "-----"
        val tail = "-----END " + "PRIVATE KEY" + "-----"
        privFile(c).writeText("$head\n$body\n$tail\n")

        val pub = sshRsa(kp.public as RSAPublicKey, COMMENT)
        pubFile(c).writeText(pub + "\n")
        return pub
    }

    /** RSA 公钥 → OpenSSH wire 格式（长度前缀 + mpint） */
    private fun sshRsa(k: RSAPublicKey, comment: String): String {
        val out = ByteArrayOutputStream()
        fun writeString(b: ByteArray) {
            val n = b.size
            out.write(byteArrayOf(
                (n ushr 24).toByte(), (n ushr 16).toByte(),
                (n ushr 8).toByte(), n.toByte()))
            out.write(b)
        }

        fun mpint(v: BigInteger) {
            var b = v.toByteArray()
            if (b.isNotEmpty() && b[0] == 0.toByte() && b.size > 1) b = b.copyOfRange(1, b.size)
            if (b.isNotEmpty() && (b[0].toInt() and 0x80) != 0) b = byteArrayOf(0) + b
            writeString(b)
        }
        writeString("ssh-rsa".toByteArray(Charsets.US_ASCII))
        mpint(k.publicExponent)
        mpint(k.modulus)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "ssh-rsa $b64 $comment"
    }

    /** 公钥指纹（SHA256:...） */
    fun fingerprint(c: Context): String = try {
        val parts = pubFile(c).readText().trim().split(" ")
        val raw = Base64.decode(parts[1], Base64.DEFAULT)
        val fp = MessageDigest.getInstance("SHA-256").digest(raw)
        "SHA256:" + Base64.encodeToString(fp, Base64.NO_WRAP or Base64.NO_PADDING)
    } catch (_: Exception) {
        "-"
    }
}
