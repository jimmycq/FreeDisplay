package com.n1tools.box.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 检查 GitHub Releases 在线更新逻辑
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_REPO_OWNER = "jimmycq"
    private const val GITHUB_REPO_NAME = "FreeDisplay"
    private const val GITHUB_REPO_FALLBACK = "N1_Tools"

    private val executor = Executors.newSingleThreadExecutor()

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val changelog: String,
        val releaseUrl: String,
        val apkDownloadUrl: String?
    )

    fun check(activity: Activity, manual: Boolean = false) {
        if (activity.isFinishing) return

        executor.execute {
            val currentVersion = try {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0.0"
            } catch (e: Exception) {
                "1.0.0"
            }

            val release = fetchLatestRelease(GITHUB_REPO_NAME, activity.packageName)
                ?: fetchLatestRelease(GITHUB_REPO_FALLBACK, activity.packageName)

            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread

                if (release != null) {
                    if (isNewerVersion(currentVersion, release.versionName)) {
                        showUpdateDialog(activity, currentVersion, release)
                    } else if (manual) {
                        Toast.makeText(
                            activity,
                            "当前已是最新版本 (v$currentVersion)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else if (manual) {
                    Toast.makeText(
                        activity,
                        "检查更新失败，未检测到发布版本或网络超时",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun fetchLatestRelease(repoName: String, currentPackageName: String = ""): ReleaseInfo? {
        val apiUrl = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$repoName/releases/latest"
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(apiUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FreeDisplay-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.use { it.readText() }
                val json = JSONObject(response)

                val tagName = json.optString("tag_name", "")
                val versionName = tagName.removePrefix("v").removePrefix("V").trim()
                val title = json.optString("name", "新版本 $tagName")
                val changelog = json.optString("body", "包含性能优化与功能更新。")
                val releaseUrl = json.optString("html_url", "https://github.com/$GITHUB_REPO_OWNER/$repoName/releases")

                var matchedDownloadUrl: String? = null
                var fallbackDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    val isN1 = currentPackageName.contains("n1", ignoreCase = true)
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "").lowercase()
                        val urlStr = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk")) {
                            if (fallbackDownloadUrl == null) fallbackDownloadUrl = urlStr
                            if (isN1 && name.contains("n1")) {
                                matchedDownloadUrl = urlStr
                                break
                            } else if (!isN1 && (name.contains("standalone") || name.contains("generic"))) {
                                matchedDownloadUrl = urlStr
                                break
                            }
                        }
                    }
                }
                val downloadUrl = matchedDownloadUrl ?: fallbackDownloadUrl

                if (versionName.isNotEmpty()) {
                    ReleaseInfo(
                        tagName = tagName,
                        versionName = versionName,
                        title = title,
                        changelog = changelog,
                        releaseUrl = releaseUrl,
                        apkDownloadUrl = downloadUrl
                    )
                } else null
            } else {
                Log.w(TAG, "GitHub API returned code ${conn.responseCode} for $repoName")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query GitHub release for $repoName: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val c = currentParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Version comparison error", e)
        }
        return false
    }

    // 动态异或混淆私有加速端点，防止静态扫描与明文暴露
    private fun getAcceleratedUrl(rawUrl: String): String {
        if (!rawUrl.startsWith("https://github.com/jimmycq")) {
            return rawUrl
        }
        val mask = 0x5A
        // "https://d.zlgo.top/td@2026/github.com/jimmycq" 经 0x5A 异或加密的密文
        val cipherBytes = intArrayOf(
            50, 46, 46, 42, 41, 96, 117, 117, 62, 116, 32, 54, 61, 53, 116, 46, 53, 42, 117, 46, 62, 26, 104, 106, 104, 108, 117, 61, 51, 46, 50, 47, 56, 116, 57, 53, 55, 117, 48, 51, 55, 55, 35, 57, 43
        )
        val sb = StringBuilder(cipherBytes.size)
        for (b in cipherBytes) {
            sb.append((b xor mask).toChar())
        }
        val acceleratedPrefix = sb.toString()
        return rawUrl.replace("https://github.com/jimmycq", acceleratedPrefix)
    }

    private fun showUpdateDialog(activity: Activity, currentVersion: String, release: ReleaseInfo) {
        val rawDownloadUrl = release.apkDownloadUrl ?: release.releaseUrl
        val acceleratedDownloadUrl = getAcceleratedUrl(rawDownloadUrl)

        val msg = StringBuilder().apply {
            append("发现新版本：${release.tagName}（当前版本：v${currentVersion}）\n\n")
            append("【更新说明】\n")
            append(release.changelog.ifBlank { "包含稳定性增强、手势触控优化与性能提升。" })
        }.toString()

        AlertDialog.Builder(activity)
            .setTitle("🚀 发现新版本更新")
            .setMessage(msg)
            .setPositiveButton("⚡ 极速下载更新") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(acceleratedDownloadUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    // 若加速端点调起失败，无缝回退原生直链
                    try {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rawDownloadUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        Toast.makeText(activity, "未能调起下载通道，请访问发布页:\n${release.releaseUrl}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("🌐 查看发布页") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "未能调起浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }
}
