package org.duzgun.eksiengelplus.gate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the update the signed policy names, checks it against the policy's
 * SHA-256, and hands it to the system installer (ported from SportGuide TV).
 * The hash is what makes the file trustworthy, not the host. Every release APK
 * is signed with the same key, so the update installs over this app and keeps
 * its data. The `github` flavor only: Play forbids an app installing its own
 * updates, so the Play build has no REQUEST_INSTALL_PACKAGES at all.
 */
object ApkInstaller {

    class HashMismatch : Exception("the download does not match the signed policy")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(60))
            .build()
    }

    fun targetFile(context: Context): File =
        File(context.cacheDir, "updates/eksiengelplus-update.apk").apply { parentFile?.mkdirs() }

    suspend fun download(
        context: Context,
        url: String,
        expectedSha256: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "update URL must be https" }
            val file = targetFile(context)
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "sunucu ${response.code} döndü" }
                val body = checkNotNull(response.body) { "boş yanıt" }
                val total = body.contentLength()
                var lastPercent = -1
                file.outputStream().use { out ->
                    copy(body.byteStream()) { buffer, length, written ->
                        out.write(buffer, 0, length)
                        digest.update(buffer, 0, length)
                        val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (!hex(digest.digest()).equals(expectedSha256, ignoreCase = true)) {
                file.delete()
                throw HashMismatch()
            }
            file
        }
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        copy(input) { buffer, length, _ -> digest.update(buffer, 0, length) }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun copy(input: InputStream, chunk: (ByteArray, Int, Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            written += read
            chunk(buffer, read, written)
        }
    }

    /** Whether this app may open the installer; the user allows it once, in system settings. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows EksiEngelPlus to install updates. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
