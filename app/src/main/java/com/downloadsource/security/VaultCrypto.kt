package com.downloadsource.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.DestroyFailedException

/**
 * 保险箱真实加密：AES-256-GCM，密钥放在 Android Keystore（不可导出、随设备绑定）。
 * 文件格式：[12 字节 IV][密文 + GCM tag]。大文件按块流式处理，不会一次性读进内存。
 */
object VaultCrypto {
    private const val KEY_ALIAS = "lm_vault_aes_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val CHUNK = 32 * 1024

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(src: File, dest: File): Boolean {
        if (!src.exists()) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val iv = cipher.iv
            FileOutputStream(dest).use { out ->
                out.write(iv)
                FileInputStream(src).use { input ->
                    val buffer = ByteArray(CHUNK)
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                    out.write(cipher.doFinal())
                }
                out.fd.sync()
            }
            dest.length() > IV_LEN
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false
        }
    }

    fun decrypt(src: File, dest: File): Boolean {
        if (!src.exists()) return false
        return try {
            FileInputStream(src).use { input ->
                val iv = ByteArray(IV_LEN)
                if (input.read(iv) != IV_LEN) return false
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv)) }
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(CHUNK)
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                    out.write(cipher.doFinal())
                }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false
        }
    }
}
