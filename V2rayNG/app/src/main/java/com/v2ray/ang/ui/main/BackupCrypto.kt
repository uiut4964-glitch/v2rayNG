package com.v2ray.ang.ui.main

import android.os.Build
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * تشفير النسخ الاحتياطي: AES-256-GCM، والمفتاح مشتق من كلمة المرور بـ PBKDF2 مع salt عشوائي.
 * صيغة النص: HOOK1:<base64( نسخة(1) | salt(16) | iv(12) | البيانات المشفرة )>
 */
object BackupCrypto {
    private const val PREFIX = "HOOK1:"
    private const val ITERATIONS = 150_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    private fun deriveKey(password: String, salt: ByteArray, algorithm: String): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val useSha256 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val algorithm = if (useSha256) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        random.nextBytes(salt)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, algorithm), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        val out = ByteArray(1 + SALT_LEN + IV_LEN + encrypted.size)
        out[0] = if (useSha256) 1 else 2
        System.arraycopy(salt, 0, out, 1, SALT_LEN)
        System.arraycopy(iv, 0, out, 1 + SALT_LEN, IV_LEN)
        System.arraycopy(encrypted, 0, out, 1 + SALT_LEN + IV_LEN, encrypted.size)
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** يعيد النص الأصلي، أو null إذا كانت كلمة المرور خاطئة أو النص غير صالح. */
    fun decrypt(text: String, password: String): String? {
        return try {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val data = Base64.decode(trimmed.removePrefix(PREFIX), Base64.DEFAULT)
            if (data.size < 1 + SALT_LEN + IV_LEN + 16) return null
            val algorithm = if (data[0].toInt() == 1) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
            val salt = data.copyOfRange(1, 1 + SALT_LEN)
            val iv = data.copyOfRange(1 + SALT_LEN, 1 + SALT_LEN + IV_LEN)
            val encrypted = data.copyOfRange(1 + SALT_LEN + IV_LEN, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, algorithm), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
