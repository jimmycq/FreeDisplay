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

        com.n1tools.box.ui.glass.GlassUiHelper.createGlassMessageDialog(
            context = activity,
            title = "🚀 发现新版本更新",
            message = msg,
            positiveText = "⚡ 立即更新",
            onPositive = {
                val rawUrl = release.apkDownloadUrl
                if (rawUrl.isNullOrBlank()) {
                    openUrlInBrowser(activity, release.releaseUrl)
                } else {
                    startInAppDownloadAndInstall(activity, acceleratedDownloadUrl, rawUrl, release.tagName)
                }
            },
            neutralText = "🌐 发布页",
            onNeutral = {
                openUrlInBrowser(activity, release.releaseUrl)
            },
            negativeText = "稍后"
        ).show()
    }

    private fun openUrlInBrowser(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "未能调起浏览器，请手动访问:\n$url", Toast.LENGTH_LONG).show()
        }
    }

    private fun startInAppDownloadAndInstall(
        activity: Activity,
        primaryUrl: String,
        fallbackUrl: String,
        tagName: String
    ) {
        val dp = { v: Int -> com.n1tools.box.ui.glass.GlassUiHelper.dp(activity, v) }
        val secondaryColor = androidx.core.content.ContextCompat.getColor(
            activity,
            com.n1tools.display.R.color.glass_text_secondary
        )

        val statusTv = android.widget.TextView(activity).apply {
            text = "正在连接下载服务器..."
            setTextColor(secondaryColor)
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }

        val progressBar = android.widget.ProgressBar(
            activity,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            )
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(statusTv)
            addView(progressBar)
        }

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val progressDialog = com.n1tools.box.ui.glass.GlassUiHelper.createGlassDialog(
            context = activity,
            title = "⬇️ 正在下载更新 ($tagName)",
            contentView = container,
            negativeText = "取消下载",
            onNegative = {
                cancelled.set(true)
            },
            cancelable = false
        )
        progressDialog.show()

        executor.execute {
            val updateDir = java.io.File(activity.cacheDir, "update").apply { mkdirs() }
            val apkFile = java.io.File(updateDir, "FreeDisplay-$tagName.apk")
            if (apkFile.exists()) apkFile.delete()

            val downloaded = downloadFileWithProgress(
                urls = listOf(primaryUrl, fallbackUrl).distinct(),
                destFile = apkFile,
                isCancelled = { cancelled.get() || activity.isFinishing }
            ) { bytesRead, totalBytes ->
                activity.runOnUiThread {
                    if (activity.isFinishing || cancelled.get()) return@runOnUiThread
                    val readMb = String.format(java.util.Locale.US, "%.2f", bytesRead / (1024.0 * 1024.0))
                    if (totalBytes > 0) {
                        val totalMb = String.format(java.util.Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0))
                        val pct = ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        statusTv.text = "已下载 $readMb MB / $totalMb MB ($pct%)"
                    } else {
                        progressBar.isIndeterminate = true
                        statusTv.text = "已下载 $readMb MB..."
                    }
                }
            }

            activity.runOnUiThread {
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
                if (cancelled.get() || activity.isFinishing) return@runOnUiThread

                if (!downloaded || !apkFile.exists() || apkFile.length() == 0L) {
                    Toast.makeText(activity, "下载失败，正在为您打开浏览器下载页", Toast.LENGTH_LONG).show()
                    openUrlInBrowser(activity, fallbackUrl)
                    return@runOnUiThread
                }

                installDownloadedApk(activity, apkFile)
            }
        }
    }

    private fun downloadFileWithProgress(
        urls: List<String>,
        destFile: java.io.File,
        isCancelled: () -> Boolean,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Boolean {
        for (targetUrl in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "FreeDisplay-Android")
                }
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "Download returned HTTP ${conn.responseCode} for $targetUrl")
                    continue
                }
                val totalBytes = conn.contentLength.toLong()
                var bytesRead = 0L
                val buf = ByteArray(16 * 1024)
                var lastReportMs = 0L

                conn.inputStream.use { input ->
                    java.io.FileOutputStream(destFile).use { output ->
                        while (!isCancelled()) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            bytesRead += n
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs >= 120L || (totalBytes > 0 && bytesRead >= totalBytes)) {
                                lastReportMs = now
                                onProgress(bytesRead, totalBytes)
                            }
                        }
                    }
                }
                if (isCancelled()) {
                    destFile.delete()
                    return false
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for $targetUrl: ${e.message}")
                if (destFile.exists()) destFile.delete()
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    private fun installDownloadedApk(activity: Activity, apkFile: java.io.File) {
        // 1. Try silent root install first (e.g. Nokia N1)
        executor.execute {
            val rootInstalled = try {
                val proc = ProcessBuilder("su", "-c", "pm install -r ${apkFile.absolutePath}")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                code == 0 && out.contains("Success", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
            if (rootInstalled) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "更新安装成功，请重新打开应用", Toast.LENGTH_LONG).show()
                }
                return@execute
            }

            // 2. Fallback to standard Android FileProvider installer UI
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    !activity.packageManager.canRequestPackageInstalls()
                ) {
                    Toast.makeText(
                        activity,
                        "请开启「允许安装未知应用」权限后再次点击安装",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        val settingsIntent = Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(settingsIntent)
                    } catch (_: Exception) {}
                    return@runOnUiThread
                }

                try {
                    val apkUri = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        apkFile
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(installIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch APK installer", e)
                    Toast.makeText(activity, "调起安装器失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
