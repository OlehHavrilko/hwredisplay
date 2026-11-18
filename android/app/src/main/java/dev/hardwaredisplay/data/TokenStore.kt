package dev.hardwaredisplay.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class SessionRecord(
    val hostId: String,
    val token: String,
    val clientId: String? = null,
    val pendingInvite: String? = null,
)

/**
 * Токены лежат шифротекстом в noBackupFilesDir: ключ в Android Keystore не покидает устройство
 * и не попадает в облачный бэкап вместе с файлом.
 */
class TokenStore(context: Context) {

    private val file = File(context.noBackupFilesDir, "sessions.bin")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): Map<String, SessionRecord> {
        if (!file.exists()) {
            return emptyMap()
        }

        return try {
            val bytes = file.readBytes()
            if (bytes.size <= IV_BYTES) {
                return emptyMap()
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
            )
            val plain = cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
            json.decodeFromString<Map<String, SessionRecord>>(plain.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun put(record: SessionRecord) {
        val records = read().toMutableMap()
        records[record.hostId] = record
        write(records)
    }

    @Synchronized
    fun remove(hostId: String) {
        val records = read().toMutableMap()
        if (records.remove(hostId) != null) {
            write(records)
        }
    }

    private fun write(records: Map<String, SessionRecord>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = json.encodeToString(records).toByteArray(Charsets.UTF_8)
        val sealed = cipher.doFinal(plain)
        file.writeBytes(cipher.iv + sealed)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hardwaredisplay.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
