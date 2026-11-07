package com.polytune.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.polytune.source.api.Credential
import com.polytune.source.api.Platform
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号凭证保险箱：AndroidKeyStore 生成不可导出的 AES 密钥，凭证以 AES/GCM 加密后落 SharedPreferences。
 * 见 [com.polytune.core.proto] 不为它设计任何跨设备导出路径。
 */
class CredentialVault private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("polytune_vault", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(c: Credential) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = Crypt.base64(cipher.iv)
            val ct = Crypt.base64(cipher.doFinal(json.encodeToString(Credential.serializer(), c).toByteArray()))
            prefs.edit().putString(c.platform.name, "$iv:$ct").apply()
        }.onFailure { Log.w(TAG, "保存凭证失败: ${it.message}") }
    }

    fun get(platform: Platform): Credential? {
        val raw = prefs.getString(platform.name, null) ?: return null
        return runCatching {
            val (iv, ct) = raw.split(":", limit = 2).let { it[0] to it[1] }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Crypt.base64Decode(iv)))
            val plain = cipher.doFinal(Crypt.base64Decode(ct)).toString(Charsets.UTF_8)
            json.decodeFromString(Credential.serializer(), plain)
        }.getOrNull()
    }

    fun all(): List<Credential> = Platform.entries.mapNotNull { get(it) }

    fun remove(platform: Platform) {
        prefs.edit().remove(platform.name).apply()
    }

    companion object {
        private const val TAG = "CredentialVault"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "polytune_vault_key"

        @Volatile private var INSTANCE: CredentialVault? = null

        fun init(context: Context) {
            if (INSTANCE == null) synchronized(this) {
                if (INSTANCE == null) INSTANCE = CredentialVault(context)
            }
        }

        fun get(): CredentialVault = INSTANCE ?: throw IllegalStateException("先调用 CredentialVault.init(applicationContext)")
    }
}
