package com.krypt.app

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val encryptedData: String,   // Base64 AES-GCM ciphertext
    val iv: String,              // Base64 IV
    val encryptedKey: String     // Base64 RSA-encrypted AES key
)

data class EncryptedFileChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val encryptedData: String,
    val iv: String,
    val encryptedKey: String,
    val fileName: String,
    val mimeType: String
)

object CryptoEngine {

    private const val RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val CHUNK_SIZE = 64 * 1024 // 64KB per chunk

    fun generateRSAKeyPair(): Pair<String, String> {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val publicKeyB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privateKeyB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        return Pair(publicKeyB64, privateKeyB64)
    }

    fun publicKeyFromBase64(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun privateKeyFromBase64(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun generateAESKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun generateIV(): ByteArray {
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        return iv
    }

    fun encryptMessage(plainText: String, recipientPublicKeyB64: String): EncryptedPayload {
        val aesKey = generateAESKey()
        val iv = generateIV()

        // AES-GCM encrypt the message
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encryptedData = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // RSA encrypt the AES key
        val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKeyFromBase64(recipientPublicKeyB64))
        val encryptedKey = rsaCipher.doFinal(aesKey.encoded)

        return EncryptedPayload(
            encryptedData = Base64.encodeToString(encryptedData, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            encryptedKey = Base64.encodeToString(encryptedKey, Base64.NO_WRAP)
        )
    }

    fun decryptMessage(payload: EncryptedPayload, privateKeyB64: String): String {
        // RSA decrypt the AES key
        val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKeyFromBase64(privateKeyB64))
        val aesKeyBytes = rsaCipher.doFinal(Base64.decode(payload.encryptedKey, Base64.NO_WRAP))
        val aesKey = SecretKeySpec(aesKeyBytes, "AES")

        // AES-GCM decrypt
        val iv = Base64.decode(payload.iv, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(Base64.decode(payload.encryptedData, Base64.NO_WRAP))
        return String(decrypted, Charsets.UTF_8)
    }

    fun encryptFileChunks(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        recipientPublicKeyB64: String
    ): List<EncryptedFileChunk> {
        val totalChunks = (fileBytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunks = mutableListOf<EncryptedFileChunk>()

        for (i in 0 until totalChunks) {
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, fileBytes.size)
            val chunkBytes = fileBytes.copyOfRange(start, end)

            val aesKey = generateAESKey()
            val iv = generateIV()

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encryptedData = cipher.doFinal(chunkBytes)

            val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKeyFromBase64(recipientPublicKeyB64))
            val encryptedKey = rsaCipher.doFinal(aesKey.encoded)

            chunks.add(
                EncryptedFileChunk(
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    encryptedData = Base64.encodeToString(encryptedData, Base64.NO_WRAP),
                    iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                    encryptedKey = Base64.encodeToString(encryptedKey, Base64.NO_WRAP),
                    fileName = fileName,
                    mimeType = mimeType
                )
            )
        }
        return chunks
    }

    fun decryptFileChunk(chunk: EncryptedFileChunk, privateKeyB64: String): ByteArray {
        val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKeyFromBase64(privateKeyB64))
        val aesKeyBytes = rsaCipher.doFinal(Base64.decode(chunk.encryptedKey, Base64.NO_WRAP))
        val aesKey = SecretKeySpec(aesKeyBytes, "AES")

        val iv = Base64.decode(chunk.iv, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(Base64.decode(chunk.encryptedData, Base64.NO_WRAP))
    }
}
