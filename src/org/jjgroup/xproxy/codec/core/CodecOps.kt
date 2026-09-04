package org.jjgroup.xproxy.codec.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CodecOps {
    private data class Delimiter(
        val value: String,
        val writeAtStart: Boolean = false,
        val writeAtEnd: Boolean = false
    )

    private val hexDelimiters: Map<String, Delimiter> = LinkedHashMap<String, Delimiter>().apply {
        put("None", Delimiter(""))
        put("Space", Delimiter(" "))
        put("Comma", Delimiter(","))
        put("Colon", Delimiter(":"))
        put("Semi-colon", Delimiter(";"))
        put("Line feed", Delimiter("\n"))
        put("CRLF", Delimiter("\r\n"))
        put("0x", Delimiter("0x", writeAtStart = true))
        put("\\x", Delimiter("\\x", writeAtStart = true))
    }

    fun toBase64(input: String, urlSafe: Boolean): String {
        return if (urlSafe) {
            Base64.getUrlEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
        } else {
            Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
        }
    }

    fun fromBase64(input: String, urlSafe: Boolean): String {
        return if (urlSafe) {
            val value = input.trim()
            val padding = (4 - value.length % 4) % 4
            val normalized = value + "=".repeat(padding)
            String(Base64.getUrlDecoder().decode(normalized), Charsets.UTF_8)
        } else {
            String(Base64.getDecoder().decode(input.trim()), Charsets.UTF_8)
        }
    }

    fun toHex(input: String, delimiterKey: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val delimiter = hexDelimiters[delimiterKey] ?: hexDelimiters.getValue("None")

        if (delimiter.value.isEmpty()) {
            return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        }

        val hex = bytes.joinToString(delimiter.value) { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        val prefix = if (bytes.isNotEmpty() && delimiter.writeAtStart) delimiter.value else ""
        val suffix = if (bytes.isNotEmpty() && delimiter.writeAtEnd) delimiter.value else ""
        return prefix + hex + suffix
    }

    fun fromHex(input: String, delimiterKey: String): String {
        val delimiter = hexDelimiters[delimiterKey] ?: hexDelimiters.getValue("None")
        var normalized = input
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
        if (delimiter.value.isNotEmpty()) {
            normalized = normalized.replace(delimiter.value, "")
        }
        if (normalized.length % 2 != 0) {
            throw IllegalArgumentException("Hex length must be even")
        }
        val bytes = normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    fun urlEncode(input: String, encodeAll: Boolean): String {
        if (encodeAll) {
            val bytes = input.toByteArray(Charsets.UTF_8)
            return bytes.joinToString("") { "%" + String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        }
        return URLEncoder.encode(input, Charsets.UTF_8)
    }

    fun urlDecode(input: String): String =
        URLDecoder.decode(input, Charsets.UTF_8)

    fun jwtDecodePayload(input: String): String {
        val parts = input.trim().split(".")
        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid JWT token")
        }
        val selected = parts[1]
        val padding = (4 - selected.length % 4) % 4
        val normalized = selected.replace('-', '+').replace('_', '/') + "=".repeat(padding)
        return String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
    }

    fun uppercase(input: String): String = input.uppercase(Locale.ROOT)

    fun lowercase(input: String): String = input.lowercase(Locale.ROOT)

    fun strip(input: String): String = input.trim()
    fun md5(input: String): String = digestHex(input, "MD5")

    fun sha1(input: String): String = digestHex(input, "SHA-1")

    fun sha256(input: String): String = digestHex(input, "SHA-256")

    fun sha512(input: String): String = digestHex(input, "SHA-512")

    fun hmac(input: String, key: String, algorithm: String, outputFormat: String): String {
        if (key.isEmpty()) {
            throw IllegalArgumentException("HMAC key must not be empty")
        }
        val macAlgorithm = normalizeHmacAlgorithm(algorithm)
        try {
            val mac = Mac.getInstance(macAlgorithm)
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), macAlgorithm))
            val digest = mac.doFinal(input.toByteArray(Charsets.UTF_8))
            if (outputFormat.equals("base64", ignoreCase = true)) {
                return Base64.getEncoder().encodeToString(digest)
            }
            return bytesToHex(digest)
        } catch (ex: GeneralSecurityException) {
            throw IllegalArgumentException("HMAC failed: ${ex.message}", ex)
        }
    }

    fun aesEncrypt(input: String, key: String, mode: String, iv: String, outputFormat: String): String {
        val plainBytes = input.toByteArray(Charsets.UTF_8)
        val encrypted = aesCipher(Cipher.ENCRYPT_MODE, plainBytes, key, mode, iv)
        return if (outputFormat.equals("hex", ignoreCase = true)) {
            bytesToHex(encrypted)
        } else {
            Base64.getEncoder().encodeToString(encrypted)
        }
    }

    fun aesDecrypt(input: String, key: String, mode: String, iv: String, inputFormat: String): String {
        val cipherBytes = if (inputFormat.equals("hex", ignoreCase = true)) {
            hexToBytes(input)
        } else {
            Base64.getDecoder().decode(input.trim())
        }
        val decrypted = aesCipher(Cipher.DECRYPT_MODE, cipherBytes, key, mode, iv)
        return String(decrypted, Charsets.UTF_8)
    }

    fun rot13(input: String): String {
        val builder = StringBuilder(input.length)
        for (value in input) {
            when {
                value in 'a'..'z' -> builder.append('a' + (value - 'a' + 13) % 26)
                value in 'A'..'Z' -> builder.append('A' + (value - 'A' + 13) % 26)
                else -> builder.append(value)
            }
        }
        return builder.toString()
    }

    fun reverse(input: String): String = input.reversed()

    fun htmlEncode(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    fun htmlDecode(input: String): String = input
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun digestHex(input: String, algorithm: String): String {
        return try {
            val digest = MessageDigest.getInstance(algorithm)
            val output = digest.digest(input.toByteArray(Charsets.UTF_8))
            bytesToHex(output)
        } catch (ex: GeneralSecurityException) {
            throw IllegalArgumentException("Digest failed: ${ex.message}", ex)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

    private fun hexToBytes(input: String): ByteArray {
        val normalized = input.trim().replace(" ", "").replace("\n", "").replace("\r", "")
        if (normalized.length % 2 != 0) {
            throw IllegalArgumentException("Hex length must be even")
        }
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun normalizeHmacAlgorithm(algorithm: String): String {
        val normalized = algorithm.trim().uppercase(Locale.ROOT).replace("_", "-")
        return when {
            normalized.isEmpty() || normalized == "SHA-256" || normalized == "HMACSHA256" -> "HmacSHA256"
            normalized == "SHA-1" || normalized == "HMACSHA1" -> "HmacSHA1"
            normalized == "SHA-512" || normalized == "HMACSHA512" -> "HmacSHA512"
            else -> throw IllegalArgumentException("Unsupported HMAC algorithm: $algorithm")
        }
    }

    private fun aesCipher(modeFlag: Int, input: ByteArray, key: String, cipherMode: String, iv: String): ByteArray {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) {
            throw IllegalArgumentException("AES key must be 16, 24, or 32 bytes (UTF-8)")
        }

        val selectedMode = cipherMode.trim().uppercase(Locale.ROOT)
        if (selectedMode != "ECB" && selectedMode != "CBC") {
            throw IllegalArgumentException("AES mode must be ECB or CBC")
        }

        val transformation = "AES/$selectedMode/PKCS5Padding"
        try {
            val cipher = Cipher.getInstance(transformation)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            if (selectedMode == "CBC") {
                val ivBytes = iv.toByteArray(Charsets.UTF_8)
                if (ivBytes.size != 16) {
                    throw IllegalArgumentException("AES CBC iv must be exactly 16 bytes (UTF-8)")
                }
                cipher.init(modeFlag, secretKey, IvParameterSpec(ivBytes))
            } else {
                cipher.init(modeFlag, secretKey)
            }
            return cipher.doFinal(input)
        } catch (ex: GeneralSecurityException) {
            throw IllegalArgumentException("AES failed: ${ex.message}", ex)
        }
    }
}
