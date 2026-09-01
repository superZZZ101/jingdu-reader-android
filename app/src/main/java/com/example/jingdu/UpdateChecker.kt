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
    val notes: String
)

private const val UPDATE_REPOSITORY_OWNER = "superZZZ101"
private const val UPDATE_REPOSITORY_NAME = "jingdu-reader-android"
private const val UPDATE_ASSET_NAME = "jingdu-reader.apk"

suspend fun checkForAppUpdate(currentVersionName: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
    if (UPDATE_REPOSITORY_OWNER == "YOUR_GITHUB_USERNAME") return@withContext null

    val endpoint = "https://api.github.com/repos/" +
        "$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/latest"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Jingdu/$currentVersionName")
    }

    try {
        if (connection.responseCode !in 200..299) return@withContext null
        val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val release = JSONObject(payload)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null

        val tagVersion = release.optString("tag_name")
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        if (compareVersions(tagVersion, currentVersionName) <= 0) return@withContext null

        val releaseUrl = release.optString("html_url").ifBlank { endpoint }
        val downloadUrl = findApkDownloadUrl(release)
        val releaseName = release.optString("name").trim().ifBlank { "v$tagVersion" }
        AppUpdateInfo(
            versionName = releaseName,
            releaseUrl = releaseUrl,
            downloadUrl = downloadUrl,
            notes = release.optString("body").trim().take(1_500)
        )
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
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
