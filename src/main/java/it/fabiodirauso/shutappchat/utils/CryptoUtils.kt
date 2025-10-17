package it.fabiodirauso.shutappchat.utils

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    fun generatePasswordHash(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return hash.fold("") { str, it -> str + "%02x".format(it) }
    }

    fun generateAESKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        return keyGenerator.generateKey()
    }

    fun generateIV(): ByteArray {
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        return iv
    }

    fun encryptAES(data: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
        return cipher.doFinal(data)
    }

    fun decryptAES(encryptedData: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
        return cipher.doFinal(encryptedData)
    }
    
    fun generateHMAC(data: String, token: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(token.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray())
        return Base64.getEncoder().encodeToString(hmacBytes)
    }
    
    fun keyToBase64(key: SecretKey): String {
        return Base64.getEncoder().encodeToString(key.encoded)
    }
    
    fun base64ToKey(base64Key: String): SecretKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        return SecretKeySpec(keyBytes, "AES")
    }
    
    fun bytesToBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }
    
    fun base64ToBytes(base64: String): ByteArray {
        return Base64.getDecoder().decode(base64)
    }
}
