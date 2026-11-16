package com.downloadsource.utils
import java.security.MessageDigest
object PasswordCrypto {
    fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    fun verifyPassword(input: String, storedHash: String?): Boolean {
        if (storedHash == null) return false
        return hashPassword(input) == storedHash
    }
}
