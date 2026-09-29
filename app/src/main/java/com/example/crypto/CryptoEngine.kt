package com.example.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedResult(
    val cipherTextBase64: String,
    val ivBase64: String,
    val algorithm: String = "AES-256-GCM"
)

object CryptoEngine {
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val KEY_ITERATIONS = 5000
    private const val KEY_LENGTH = 256

    private val secureRandom = SecureRandom()

    // 12-word dictionary for sync phrase generation
    private val SYNC_WORD_LIST = listOf(
        "cipher", "whisper", "shield", "horizon", "canvas", "ripple",
        "matrix", "zenith", "orbit", "echo", "quartz", "beacon",
        "solace", "timber", "aurora", "prism", "shadow", "glacier",
        "nebula", "ember", "vortex", "cobalt", "harbor", "haven"
    )

    fun generateSalt(): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun deriveKey(seed: String, salt: String): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(seed.toCharArray(), salt.toByteArray(Charsets.UTF_8), KEY_ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encrypt(plainText: String, secretKey: SecretKey): EncryptedResult {
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedResult(
            cipherTextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decrypt(cipherTextBase64: String, ivBase64: String, secretKey: SecretKey): String {
        return try {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "[Decryption Error: Key mismatch or tampered payload]"
        }
    }

    fun hashPassword(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray(Charsets.UTF_8))
        val hashed = md.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashed, Base64.NO_WRAP)
    }

    fun generateSafetyNumbers(userA: String, userB: String): String {
        // Deterministic safety number generated between two participant identifiers
        val combined = listOf(userA.lowercase(), userB.lowercase()).sorted().joinToString("::")
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(combined.toByteArray(Charsets.UTF_8))
        val bigIntString = digest.fold(StringBuilder()) { acc, byte ->
            acc.append(String.format("%02d", (byte.toInt() and 0xFF) % 100))
        }.toString()

        // 60 digits split into chunks of 5
        val truncated = (bigIntString + "7382910482910482019482019482019482019482").take(60)
        return truncated.chunked(5).take(12).joinToString(" ")
    }

    fun generateSyncPhrase(): String {
        val words = mutableListOf<String>()
        val pool = SYNC_WORD_LIST.shuffled(secureRandom)
        for (i in 0 until 12) {
            words.add(pool[i % pool.size])
        }
        return words.joinToString(" ")
    }

    fun getFingerprintPreview(user: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(user.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString(":") { String.format("%02X", it) }
    }
}
