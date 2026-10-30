package com.fwy13.signbridge.data

import android.content.Context
import com.fwy13.signbridge.network.Http
import com.fwy13.signbridge.models.GitHubReleaseDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DictionaryRepository(private val context: Context, private val http: Http, private val prefs: Preferences) {
    val data = MutableStateFlow(JSONObject())
    private val lock = Mutex()
    private fun file(region: String): File { require(region in setOf("hoa_de", "vsl")); return File(context.filesDir, "dictionary_$region.json") }
    suspend fun load(region: String) = withContext(Dispatchers.IO) {
        data.value = runCatching { unpack(file(region).readText()) }.getOrElse { JSONObject() }
    }
    private fun unpack(text: String): JSONObject { val root = JSONObject(text); return root.optJSONObject("data") ?: root }
    /** Each published release may contain both original dictionary variants. */
    suspend fun sync(region: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val release = GitHubReleaseDownloader.fetchReleases().firstOrNull { item ->
                item.assets.any { it.name == "dictionary_hoa_de.json" } &&
                    item.assets.any { it.name == "dictionary_vsl.json" }
            } ?: return@withContext // Models-only release: keep the existing cache.

            // Install both versions together, to avoid mixing releases.
            val names = listOf("dictionary_hoa_de.json", "dictionary_vsl.json")
            if (names.all { name -> File(context.filesDir, name).isFile } &&
                prefs.dictionaryReleaseTag() == release.tagName) return@withContext

            val staging = File(context.cacheDir, "dictionary-release-${java.util.UUID.randomUUID()}")
            try {
                val assets = names.map { name -> release.assets.single { it.name == name } }
                GitHubReleaseDownloader.downloadAssets(assets, staging) { _, _, _, _ -> }
                // Validate both documents before replacing either one.
                val parsed = names.map { name ->
                    val file = File(staging, name)
                    require(file.isFile && file.length() in 2L..(30L * 1024 * 1024)) {
                        "Invalid vocabulary asset: $name"
                    }
                    unpack(file.readText(Charsets.UTF_8)).also {
                        require(it.length() > 0) { "Empty vocabulary: $name" }
                    }
                }
                names.forEachIndexed { index, name ->
                    val target = File(context.filesDir, name)
                    Files.move(File(staging, name).toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
                }
                prefs.dictionaryReleaseTag(release.tagName)
                val selectedIndex = if (prefs.state.value.region == "hoa_de") 0 else 1
                if (prefs.state.value.region == region) data.value = parsed[selectedIndex]
            } finally {
                staging.deleteRecursively()
            }
        }
    }
    fun find(text: String): String? {
        val source = data.value
        val query = text.trim().lowercase()
        val plain = normalize(query)
        fun variant(value: JSONObject?): String? {
            val variants = value?.optJSONObject("variants") ?: return null
            return listOf("actor_male", "actor_female", "default").firstNotNullOfOrNull { variants.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } }
        }
        for (slug in listOf(plain.replace(Regex("\\s+"), "_"), query.replace(Regex("\\s+"), "_"))) {
            if (source.has(slug)) return variant(source.optJSONObject(slug))
        }
        source.keys().forEach { key -> if (normalize(source.getJSONObject(key).optString("word")) == plain) return variant(source.getJSONObject(key)) }
        source.keys().forEach { key -> if (source.getJSONObject(key).optString("word").lowercase() == query) return variant(source.getJSONObject(key)) }
        return null
    }
}
