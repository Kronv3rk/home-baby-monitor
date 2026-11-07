package com.homebabymonitor.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An ephemeral high-entropy pairing secret, exchanged by reading the baby's screen.
 * 20 Crockford base32 symbols provide 100 bits of entropy; never transmit the secret.
 */
object PairingCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val rng = SecureRandom()
    private val decoder = Base64.getUrlDecoder()
    fun generate(): String {
        val entropy = ByteArray(20).also(rng::nextBytes)
        return entropy.map { ALPHABET[(it.toInt() and 255) % 32] }.joinToString("")
            .chunked(4).joinToString("-")
    }
    fun normalize(raw: String): String? {
        val compact = raw.uppercase(java.util.Locale.ROOT)
            .filterNot { it == '-' || it == ' ' }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        return compact.takeIf { it.length == 20 && it.all(ALPHABET::contains) }
    }
    fun validSession(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 2 && validChallenge(parts[0]) && validChallenge(parts[1])
    }
    fun newChallenge(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(16).also(rng::nextBytes))
    fun validChallenge(value: String): Boolean = value.length == 22 &&
        runCatching { decoder.decode(value).size == 16 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoder.decode(value)) == value }.getOrDefault(false)
}

/**
 * AES-256-GCM on top of TCP, with independent directional keys and monotonically
 * increasing nonce counters. A random per-connection server challenge derives new keys.
 * Invalid/replayed/out-of-order frames fail authentication and close the connection.
 */
class SecureSignalCipher(secret: String, challenge: String, isServer: Boolean) {
    private val keySend: SecretKeySpec
    private val keyReceive: SecretKeySpec
    private val aad = ("HomeBabyMonitor-signalling-v3:" + challenge).toByteArray(Charsets.US_ASCII)
    private var sendCounter = 0L
    private var receiveCounter = 0L
    init {
        val code = PairingCode.normalize(secret) ?: throw IllegalArgumentException("Неверный формат кода")
        require(PairingCode.validSession(challenge)) { "Неверный вызов сопряжения" }
        val root = MessageDigest.getInstance("SHA-256")
            .digest(("HomeBabyMonitor-secret-v3:" + code).toByteArray(Charsets.US_ASCII))
        fun key(direction: String): SecretKeySpec {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(root, "HmacSHA256"))
            val bytes = mac.doFinal(("HomeBabyMonitor-key-v3:" + direction + ":" + challenge).toByteArray(Charsets.US_ASCII))
            return SecretKeySpec(bytes, "AES")
        }
        keySend = key(if (isServer) "server-to-client" else "client-to-server")
        keyReceive = key(if (isServer) "client-to-server" else "server-to-client")
        root.fill(0)
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12)
        .putInt(0).putLong(counter).array()

    @Synchronized fun seal(plain: String): String {
        if (sendCounter == Long.MAX_VALUE) throw GeneralSecurityException("Nonce exhausted")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySend, GCMParameterSpec(128, nonce(sendCounter)))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        sendCounter++
        return "ENC " + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)
    }

    @Synchronized fun open(line: String): String {
        if (!line.startsWith("ENC ") || line.length > WireProtocol.MAX_LINE || receiveCounter == Long.MAX_VALUE)
            throw GeneralSecurityException("Encrypted frame required")
        val encrypted = Base64.getUrlDecoder().decode(line.substring(4))
        if (encrypted.size < 16) throw GeneralSecurityException("Frame too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyReceive, GCMParameterSpec(128, nonce(receiveCounter)))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(encrypted)
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(plaintext)).toString()
        receiveCounter++
        return text
    }
}
