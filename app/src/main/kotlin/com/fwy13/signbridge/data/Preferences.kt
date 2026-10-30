package com.fwy13.signbridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Pronouns(val id: String = "toi_ban", val speaker: String = "tôi", val listener: String = "bạn", val addSubject: Boolean = true) {
    val label: String get() = if (addSubject) "$speaker – $listener" else "Không tự thêm chủ ngữ"
}
val pronounPresets = listOf(
    Pronouns(), Pronouns("minh_ban", "mình", "bạn"), Pronouns("to_cau", "tớ", "cậu"),
    Pronouns("toi_anh_chi", "tôi", "anh/chị"), Pronouns("em_anh_chi", "em", "anh/chị"),
    Pronouns("anh_chi_em", "anh/chị", "em"), Pronouns("none", "", "", false)
)
data class Settings(val language: String = "vi", val dark: Boolean = false, val region: String = "", val pronouns: Pronouns = Pronouns())
class Preferences(context: Context) {
    private val systemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val prefs = context.getSharedPreferences("signbridge", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read() = Settings(prefs.getString("language", "vi") ?: "vi", prefs.getBoolean("dark", systemDark),
        prefs.getString("region", "") ?: "", Pronouns(prefs.getString("pronounId", "toi_ban") ?: "toi_ban",
            prefs.getString("speaker", "tôi") ?: "tôi", prefs.getString("listener", "bạn") ?: "bạn", prefs.getBoolean("addSubject", true)))
    fun update(value: Settings) {
        prefs.edit().putString("language", value.language).putBoolean("dark", value.dark).putString("region", value.region)
            .putString("pronounId", value.pronouns.id).putString("speaker", value.pronouns.speaker)
            .putString("listener", value.pronouns.listener).putBoolean("addSubject", value.pronouns.addSubject).apply()
        mutable.value = value
    }
    fun clear() { prefs.edit().clear().apply(); mutable.value = Settings(dark = systemDark) }
    fun dictionaryReleaseTag(): String = prefs.getString("dictionary_release_tag", "") ?: ""
    fun dictionaryReleaseTag(tag: String) { prefs.edit().putString("dictionary_release_tag", tag).apply() }
    fun dictionaryVersion(region: String) = prefs.getInt("dict_$region", 0)
    fun dictionaryVersion(region: String, version: Int) { prefs.edit().putInt("dict_$region", version).apply() }
}

/** Tokens are encrypted with a device-bound Android Keystore key. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("signbridge-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("signbridge-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? = runCatching {
        val value = prefs.getString("session", null) ?: return null
        val parts = value.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }.getOrNull()
    fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("session", encoded).commit()) { "Cannot persist session" }
    }
    fun clear() { prefs.edit().clear().commit() }
}

class Texts(val language: String) {
    operator fun invoke(key: String, vararg values: Pair<String, Any>): String {
        var text = (if (language == "en") texts_en else texts_vi)[key] ?: texts_vi[key] ?: key
        values.forEach { (name, value) -> text = text.replace("{{$name}}", value.toString()) }
        return text
    }
}
