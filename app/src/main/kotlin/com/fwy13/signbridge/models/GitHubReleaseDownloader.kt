package com.fwy13.signbridge.models

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val uploaderLogin: String,
    val uploaderUrl: String,
    val assets: List<ReleaseAsset>
)

object GitHubReleaseDownloader {
    private const val OWNER = "fwy13"
    private const val REPO = "SignBridge-Assets"

    suspend fun fetchReleases(
        @Suppress("UNUSED_PARAMETER") tag: String = "",
    ): List<GitHubRelease> = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SignBridge-Android")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("Error GitHub API: HTTP ${conn.responseCode}")
            }
            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(jsonStr)
            (0 until array.length()).map { i ->
                val rel = array.getJSONObject(i)
                val assetsJson = rel.getJSONArray("assets")
                val assets = (0 until assetsJson.length()).map { j ->
                    val a = assetsJson.getJSONObject(j)
                    ReleaseAsset(
                        name = a.getString("name"),
                        downloadUrl = a.getString("browser_download_url"),
                        sizeBytes = a.getLong("size")
                    )
                }
                GitHubRelease(
                    tagName = rel.getString("tag_name"),
                    name = rel.optString("name").ifBlank { rel.getString("tag_name") },
                    body = rel.optString("body", ""),
                    publishedAt = rel.optString("published_at", rel.optString("created_at", "")),
                    uploaderLogin = rel.optJSONObject("author")?.optString("login").orEmpty(),
                    uploaderUrl = rel.optJSONObject("author")?.optString("html_url").orEmpty(),
                    assets = assets
                )
            }.filter { it.assets.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun downloadAssets(
        assets: List<ReleaseAsset>,
        targetDir: File,
        onProgress: (fileName: String, currentFile: Int, totalFiles: Int, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        check(targetDir.isDirectory || targetDir.mkdirs()) { "Cannot create download directory" }

        assets.forEachIndexed { index, asset ->
            currentCoroutineContext().ensureActive()
            require(asset.name.isNotBlank() && asset.name == File(asset.name).name &&
                '/' !in asset.name && '\\' !in asset.name && asset.name !in setOf(".", "..")) { "Invalid asset filename" }
            require(URL(asset.downloadUrl).protocol == "https") { "HTTPS download required" }
            onProgress(asset.name, index + 1, assets.size, 0)
            val tempFile = File(targetDir, "${asset.name}.tmp")
            val finalFile = File(targetDir, asset.name)

            val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SignBridge-Android")
                connectTimeout = 15000
                readTimeout = 30000
            }

            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    error("Can't download ${asset.name}: HTTP ${conn.responseCode}")
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100) / totalBytes).coerceIn(0L, 100L).toInt()
                                onProgress(asset.name, index + 1, assets.size, pct)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                require(downloadedBytes > 0L) { "Downloaded file is empty" }
                if (asset.sizeBytes > 0L) require(downloadedBytes == asset.sizeBytes) { "Incomplete download: ${asset.name}" }
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
            } finally {
                conn.disconnect()
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }
}