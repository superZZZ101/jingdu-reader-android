package com.example.jingdu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Reads the latest public GitHub Release and compares its tag with this app version. */
data class AppUpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    /** Mirrors to try when the direct GitHub download is unreachable. */
    val downloadMirrors: List<String> = emptyList(),
    val notes: String = ""
)

data class AppUpdateCheckResult(
    val update: AppUpdateInfo?,
    val succeeded: Boolean
)

private const val UPDATE_REPOSITORY_OWNER = "superZZZ101"
private const val UPDATE_REPOSITORY_NAME = "jingdu-reader-android"
private const val UPDATE_ASSET_NAME = "app-release.apk"

// api.github.com is unreachable from some networks (notably mainland China mobile networks), which
// made the update check fail silently. These mirrors serve the same release JSON / assets.
private val RELEASE_ENDPOINTS = listOf(
    "https://api.github.com/repos/%s/%s/releases/latest",
    "https://api.kkgithub.com/repos/%s/%s/releases/latest",
    "https://gh-proxy.com/https://api.github.com/repos/%s/%s/releases/latest",
    "https://ghfast.top/https://api.github.com/repos/%s/%s/releases/latest"
)

private val DOWNLOAD_MIRROR_PREFIXES = listOf(
    "https://ghproxy.net/",
    "https://gh-proxy.com/",
    "https://ghfast.top/"
)

suspend fun checkForAppUpdate(currentVersionName: String): AppUpdateCheckResult = withContext(Dispatchers.IO) {
    if (UPDATE_REPOSITORY_OWNER == "YOUR_GITHUB_USERNAME") {
        return@withContext AppUpdateCheckResult(update = null, succeeded = true)
    }

    var fallback: AppUpdateInfo? = null
    var reachable = false
    for (template in RELEASE_ENDPOINTS) {
        val endpoint = template.format(UPDATE_REPOSITORY_OWNER, UPDATE_REPOSITORY_NAME)
        val release = fetchRelease(endpoint) ?: continue
        reachable = true
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue

        val tagVersion = release.optString("tag_name")
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        if (compareVersions(tagVersion, currentVersionName) <= 0) continue

        val releaseUrl = release.optString("html_url").ifBlank { endpoint }
        val downloadUrl = findApkDownloadUrl(release)
        val releaseName = release.optString("name").trim().ifBlank { "v$tagVersion" }
        val info = AppUpdateInfo(
            versionName = releaseName,
            releaseUrl = releaseUrl,
            downloadUrl = downloadUrl,
            downloadMirrors = mirrorDownloadUrls(downloadUrl, endpoint),
            notes = release.optString("body").trim().take(1_500)
        )
        // A mirror that answered is the proof that the direct host is unreachable, so prefer its
        // download link; otherwise keep looking in case a later endpoint is the reachable one.
        if (endpoint != RELEASE_ENDPOINTS.first()) return@withContext AppUpdateCheckResult(info, true)
        fallback = info
    }
    AppUpdateCheckResult(update = fallback, succeeded = reachable)
}

private fun mirrorDownloadUrls(downloadUrl: String?, endpoint: String): List<String> {
    if (downloadUrl.isNullOrBlank()) return emptyList()
    if (!endpoint.startsWith("https://api.github.com/")) {
        // Already answered by a mirror: keep the direct URL last as a backup.
        return listOf(downloadUrl) + DOWNLOAD_MIRROR_PREFIXES.map { it + downloadUrl }
    }
    return DOWNLOAD_MIRROR_PREFIXES.map { it + downloadUrl } + downloadUrl
}

private fun fetchRelease(endpoint: String): JSONObject? {
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Jingdu/update-check")
    }
    return try {
        if (connection.responseCode !in 200..299) return null
        val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        JSONObject(payload)
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

/**
 * Picks the first download URL this device can actually reach. GitHub's asset host is blocked on
 * some networks, so probing beats handing the browser a link that opens an error page.
 */
suspend fun firstReachableDownloadUrl(candidates: List<String>): String? = withContext(Dispatchers.IO) {
    val unique = candidates.filter { it.isNotBlank() }.distinct()
    if (unique.size == 1) return@withContext unique.first()
    for (url in unique) {
        val reachable = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "Jingdu/update-check")
            }
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        if (reachable) return@withContext url
    }
    unique.lastOrNull()
}

private fun findApkDownloadUrl(release: JSONObject): String? {
    val assets = release.optJSONArray("assets") ?: return null
    var fallback: String? = null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        val name = asset.optString("name").trim()
        val url = asset.optString("browser_download_url").trim()
        if (url.isEmpty()) continue
        if (name.equals(UPDATE_ASSET_NAME, ignoreCase = true)) return url
        if (fallback == null && name.endsWith(".apk", ignoreCase = true)) fallback = url
    }
    return fallback
}

private fun compareVersions(left: String, right: String): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    if (leftParts.isEmpty() || rightParts.isEmpty()) return 0
    val count = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until count) {
        val leftPart = leftParts.getOrElse(index) { 0 }
        val rightPart = rightParts.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }
    return 0
}

private fun versionParts(value: String): List<Int> = Regex("\\d+")
    .findAll(value)
    .mapNotNull { it.value.toIntOrNull() }
    .toList()
