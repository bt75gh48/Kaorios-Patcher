package com.kaorios.patcher.domain.actions

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP download helper cho asset của GitHub release.
 *
 * - `resolveReleaseAsset(owner, repo, tag | null, extension)` — trả `Asset(name, url)` của asset
 *   đầu tiên có đuôi khớp. `tag = null` → dùng endpoint `/releases/latest`.
 * - `downloadAsset(url, cacheKey, ext)` — stream download với cache local.
 *
 * Không thêm dependency mới (dùng `HttpURLConnection`).
 */
object DexDownloader {

    private const val TAG = "DexDownloader"
    private val json = Json { ignoreUnknownKeys = true }

    data class Asset(val name: String, val downloadUrl: String, val size: Long)

    /**
     * Auto-resolve asset từ GitHub release:
     * - Nếu [tag] != null → gọi `/releases/tags/{tag}`.
     * - Nếu null → `/releases/latest`.
     *
     * Filter assets theo `name.endsWith(".$extension")`, ignoreCase.
     */
    suspend fun resolveReleaseAsset(
        repoOwner: String,
        repoName: String,
        tag: String?,
        extension: String
    ): Asset = withContext(Dispatchers.IO) {
        val apiUrl = if (tag.isNullOrBlank())
            "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
        else
            "https://api.github.com/repos/$repoOwner/$repoName/releases/tags/$tag"
        LogSink.d(TAG, "GitHub API: $apiUrl")

        val body = httpGetText(apiUrl) { code ->
            if (code == 404) error(
                if (tag == null) "Repo $repoOwner/$repoName không có release nào."
                else "Không tìm thấy release có tag '$tag' tại $repoOwner/$repoName."
            )
        }

        val release = json.parseToJsonElement(body).jsonObject
        val assets: JsonArray = release["assets"]?.jsonArray
            ?: error("Response không có trường 'assets'.")
        if (assets.isEmpty()) error("Release chưa có asset nào.")

        val match: JsonObject = assets.map { it.jsonObject }
            .firstOrNull { a ->
                a["name"]?.jsonPrimitive?.content?.endsWith(".$extension", ignoreCase = true) == true
            } ?: error(
                "Release ${tag ?: "(latest)"} không có asset .$extension. " +
                    "Assets hiện có: ${assets.joinToString { it.jsonObject["name"]?.jsonPrimitive?.content ?: "?" }}"
            )

        val name = match["name"]?.jsonPrimitive?.content ?: "?"
        val url = match["browser_download_url"]?.jsonPrimitive?.content
            ?: error("Asset $name không có browser_download_url.")
        val size = match["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
        LogSink.i(TAG, "asset .$extension · $name (${size}B)")
        LogSink.d(TAG, "  url: $url")
        Asset(name, url, size)
    }

    /** Shortcut: resolve + download theo tag. */
    suspend fun downloadReleaseDex(
        ctx: Context,
        repoOwner: String,
        repoName: String,
        tag: String,
        cacheKey: String,
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        val asset = resolveReleaseAsset(repoOwner, repoName, tag, "dex")
        return downloadAsset(ctx, asset.downloadUrl, cacheKey, "dex", progress)
    }

    /** Resolve + download asset `.zip`. Nếu [tag] null → latest. */
    suspend fun downloadReleaseZip(
        ctx: Context,
        repoOwner: String,
        repoName: String,
        tag: String?,
        cacheKey: String,
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        val asset = resolveReleaseAsset(repoOwner, repoName, tag, "zip")
        return downloadAsset(ctx, asset.downloadUrl, cacheKey, "zip", progress)
    }

    /**
     * Download [url] về `<ctx.cacheDir>/downloads/<cacheKey>.<ext>`. Cache hit nếu file tồn tại & > 0 bytes.
     */
    suspend fun downloadAsset(
        ctx: Context,
        url: String,
        cacheKey: String,
        ext: String,
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val cacheDir = File(ctx.cacheDir, "downloads").apply { mkdirs() }
        val dest = File(cacheDir, "$cacheKey.$ext")
        dest.delete()
        if (dest.exists() && dest.length() > 0L) {
            LogSink.detail(TAG, "download cache hit · ${dest.name} (${dest.length()}B)")
            return@withContext dest
        }
        val tmp = File(cacheDir, "$cacheKey.$ext.part")
        tmp.delete()

        LogSink.i(TAG, "downloading → ${dest.name}")
        LogSink.d(TAG, "  $url")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "KaoriosPatcher")
                setRequestProperty("Accept", "application/octet-stream")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                error("HTTP $code ${conn.responseMessage ?: ""}${err?.let { " — $it" } ?: ""}")
            }
            val total = conn.contentLengthLong
            LogSink.d(TAG, "HTTP OK, length=$total")

            var downloaded = 0L
            conn.inputStream.use { ins ->
                tmp.outputStream().buffered(128 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        progress(downloaded, total)
                    }
                    out.flush()
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true); tmp.delete()
            }
            LogSink.detail(TAG, "saved · ${dest.name} (${dest.length()}B)")
            dest
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun httpGetText(apiUrl: String, onErrorCode: (Int) -> Unit): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "KaoriosPatcher")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                onErrorCode(code)
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                error("GitHub API HTTP $code ${conn.responseMessage ?: ""}${err?.let { " — $it" } ?: ""}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun clearCache(ctx: Context) {
        File(ctx.cacheDir, "downloads").deleteRecursively()
    }
}
