package com.naling.shuai

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 棂记 v2.17 · 加密导出格式 .linji（2026-09-21 作者要求：导出不再是 .json）
 *  文件结构： [魔数 "LINJI1"(6B)] [盐 16B] [IV 12B] [AES-256-GCM 密文+16B tag]
 *  口令派生： PBKDF2-HMAC-SHA256（老系统自动回退 SHA1），20 万次
 *  → 没有口令打不开；同一套口径与 agent-keynl 一致（AES-256-GCM + PBKDF2）
 */
object Linji {

    private const val MAGIC = "LINJI1"
    private const val ITER = 200_000
    private const val SALT = 16
    private const val IV = 12

    private fun derive(pwd: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pwd.toCharArray(), salt, ITER, 256)
        val kf = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: Exception) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return SecretKeySpec(kf.generateSecret(spec).encoded, "AES")
    }

    /** 明文 → .linji 字节 */
    fun seal(plain: String, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt); out.write(iv); out.write(ct)
        return out.toByteArray()
    }

    /** .linji 字节 → 明文（口令错会抛 AEADBadTagException） */
    fun open(data: ByteArray, password: String): String {
        if (data.size < MAGIC.length + SALT + IV + 16) throw IllegalArgumentException("文件不是 .linji 或已损坏")
        val magic = String(data, 0, MAGIC.length, Charsets.US_ASCII)
        if (magic != MAGIC) throw IllegalArgumentException("不是棂记导出的 .linji 文件（缺少 LINJI1 头）")
        val salt = data.copyOfRange(MAGIC.length, MAGIC.length + SALT)
        val iv = data.copyOfRange(MAGIC.length + SALT, MAGIC.length + SALT + IV)
        val ct = data.copyOfRange(MAGIC.length + SALT + IV, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    fun isLinji(name: String) = name.endsWith(".linji", true)
}
