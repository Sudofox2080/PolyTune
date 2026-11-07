package com.polytune.core.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object Crypt {

    private const val HEX = "0123456789abcdef"

    fun md5(input: String, upper: Boolean = false): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return if (upper) sb.toString().uppercase() else sb.toString()
    }

    fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun base64(input: String): String = base64(input.toByteArray(Charsets.UTF_8))

    fun base64Decode(input: String): ByteArray =
        android.util.Base64.decode(input, android.util.Base64.NO_WRAP)

    /** AES-128-ECB PKCS7，网易 eapi 的参数加密。 */
    fun aesEcbBase64(plain: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return base64(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** AES-128-ECB PKCS7，输出大写 hex（无分隔），网易 eapi 的线上约定。 */
    fun aesEcbHex(plain: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789ABCDEF"[v shr 4])
            sb.append("0123456789ABCDEF"[v and 0x0F])
        }
        return sb.toString()
    }

    fun aesCbcBase64(plain: ByteArray, key: ByteArray = "e82ckenh8dichen8".toByteArray()): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec("0102030405060708".toByteArray()),
        )
        return base64(cipher.doFinal(plain))
    }

    /** 大整数模幂，实现 RSA 公钥加密（无填充），weapi 的两次加密用。 */
    fun rsaNoPadding(plain: ByteArray, modulusHex: String, exponentHex: String): String {
        val m = java.math.BigInteger(modulusHex, 16)
        val e = java.math.BigInteger(exponentHex, 16)
        val msg = java.math.BigInteger(1, plain)
        val c = msg.modPow(e, m)
        var hex = c.toString(16)
        if (hex.length % 2 != 0) hex = "0$hex"
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return base64(bytes)
    }

    /** QQ 音乐 comm 字段：base64(JSON) 再做一层 base64。 */
    fun doubleBase64(json: String): String = base64(base64(json))

    /**
     * 跨源同曲归一化指纹：去空格标点与常见后缀（live/remix/伴奏/feat），统一小写。
     */
    fun fingerprint(title: String, artist: String, album: String = ""): String {
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("\\(.*?\\)|（.*?）|\\[.*?]|【.*?】"), "")
            .replace(Regex("\\s+"), "")
            .replace(Regex("[\\p{Punct}]"), "")
        return md5(norm(title) + "|" + norm(artist.takeWhile { it != '/' && it != '&' && it != ',' }) + "|" + norm(album))
    }
}
