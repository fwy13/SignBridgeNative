package com.fwy13.signbridge.network

import android.net.Uri
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Temporary compatibility downloader for QIPEDC's expired TLS certificate.
 *
 * Certificate-chain validation is disabled only for the allowlisted QIPEDC MP4
 * endpoint. The downloaded file is then played from the app's private cache.
 * Remove this module after QIPEDC renews its certificate or the videos are moved
 * to a maintained CDN.
 */
class VideoRepository(private val context: Context) {
    private val cacheLock = Mutex()
    suspend fun playable(remoteUrl: String): String = withContext(Dispatchers.IO) {
        val parsed = URI(remoteUrl)
        if (!parsed.host.equals(ALLOWED_HOST, true)) return@withContext remoteUrl
        cacheLock.withLock {
            val uri = validateUrl(remoteUrl)
            val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            val destination = File(directory, "${sha256(remoteUrl)}.mp4")
            if (!destination.isFile || destination.length() == 0L) download(uri.toURL(), destination)
            Uri.fromFile(destination).toString()
        }
    }

    private fun validateUrl(remoteUrl: String): URI {
        val uri = URI(remoteUrl)
        val isAllowed =
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(ALLOWED_HOST, ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                VIDEO_PATH.matches(uri.path.orEmpty())

        require(isAllowed) { "URL video QIPEDC không hợp lệ" }
        return uri
    }

    private fun download(url: URL, destination: File) {
        val temporaryFile = File(destination.parentFile, "${destination.name}.download")
        temporaryFile.delete()

        val connection = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = insecureSocketFactory()
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://qipedc.moet.gov.vn/")
        }

        try {
            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                throw IllegalStateException("QIPEDC trả về HTTP $status")
            }

            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.startsWith("video/mp4")) {
                throw IllegalStateException("QIPEDC không trả về video MP4")
            }

            val declaredSize = connection.contentLengthLong
            if (declaredSize > MAX_VIDEO_BYTES) {
                throw IllegalStateException("Video QIPEDC vượt quá giới hạn cho phép")
            }

            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        totalBytes += bytesRead
                        if (totalBytes > MAX_VIDEO_BYTES) {
                            throw IllegalStateException("Video QIPEDC vượt quá giới hạn cho phép")
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                    output.fd.sync()
                }
            }

            if (temporaryFile.length() == 0L) {
                throw IllegalStateException("Video QIPEDC tải về bị rỗng")
            }
            if (!temporaryFile.renameTo(destination)) {
                throw IllegalStateException("Không thể lưu video QIPEDC")
            }
        } finally {
            connection.disconnect()
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun insecureSocketFactory() = SSLContext.getInstance("TLS").run {
        val trustAllManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        socketFactory
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val ALLOWED_HOST = "qipedc.moet.gov.vn"
        private const val CACHE_DIRECTORY = "qipedc_videos"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_VIDEO_BYTES = 50L * 1024L * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private val VIDEO_PATH = Regex("^/videos/[A-Za-z0-9_-]+\\.mp4$")

    }
}
