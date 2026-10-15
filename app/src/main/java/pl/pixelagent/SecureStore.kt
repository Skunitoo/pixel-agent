package pl.pixelagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class SecureStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("pixel_agent_local", Context.MODE_PRIVATE)
    private val alias = "pixel_agent_oauth_aes_v1"

    fun stableHostId(): String {
        val current = preferences.getString("host_id", null)
        if (!current.isNullOrEmpty()) return current
        val created = "urn:uuid:" + java.util.UUID.randomUUID().toString()
        check(preferences.edit().putString("host_id", created).commit())
        return created
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    @Synchronized fun getSession(): JSONObject? {
        val raw = preferences.getString("session", null) ?: return null
        return try {
            val parts = raw.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    @Synchronized fun saveSession(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        check(preferences.edit().putString("session", iv + ":" + encrypted).commit())
    }

    @Synchronized fun signOut() {
        check(preferences.edit().remove("session").commit())
    }
}
