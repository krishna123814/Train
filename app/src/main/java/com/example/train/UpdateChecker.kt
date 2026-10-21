package com.example.train

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: GitHub latest release dekhta hai (tag "build-<versionCode>"),
 * APK cache me download karta hai, phir Android ka install screen kholta hai.
 * Repo ka naam BuildConfig.UPDATE_REPO se aata hai (workflow -PgithubRepo deta hai).
 */
object UpdateChecker {

    data class Info(val versionCode: Long, val url: String)

    /** Check me naya version mila to yahan rakha jata hai (service notification ke liye). */
    @Volatile
    var pending: Info? = null

    /** Download ho chuka par install screen khud nahi khuli -> notification "Install karo" isse chalti hai. */
    @Volatile
    var readyInstallIntent: Intent? = null

    private val main = Handler(Looper.getMainLooper())

    // ── local version ─────────────────────────────────────────────────────
    private fun localVersionCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    // ── check ─────────────────────────────────────────────────────────────
    /** Background me check; result main thread par. Koi bhi dikkat -> null (silently). */
    fun check(ctx: Context, onResult: (Info?) -> Unit) {
        val app = ctx.applicationContext
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isEmpty()) {
            main.post { onResult(null) }
            return
        }
        Thread {
            var result: Info? = null
            var reached = false
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("User-Agent", "Train")
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val remoteVc = json.optString("tag_name").removePrefix("build-").toLongOrNull()
                        var apkUrl: String? = null
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                    apkUrl = a.optString("browser_download_url")
                                    break
                                }
                            }
                        }
                        val u = apkUrl
                        if (remoteVc != null && u != null && u.isNotEmpty() &&
                            remoteVc > localVersionCode(app)
                        ) {
                            result = Info(remoteVc, u)
                        }
                        reached = true
                    } else if (code == 404) {
                        reached = true // abhi koi release nahi
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                result = null
            }
            val r = result
            main.post { onResult(r) }
        }.start()
    }

    // ── install permission (Android 8+) ───────────────────────────────────
    /** false = permission nahi hai; settings khol diya + Toast diya, caller ruk jaye. */
    fun ensureInstallPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            try {
                val i = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e: Exception) {
            }
            Toast.makeText(
                ctx, "Train ko install allow karo, phir Update dobara dabao", Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    // ── download + install ────────────────────────────────────────────────
    /**
     * APK cache/updates/Train.apk me download karke install screen kholta hai.
     * Sab callback main thread par. onProgress(100) download poora hone par ek baar aata hai.
     * Install screen khulne me exception aaye to onInstallFailed(intent) (notification "Install karo" ke liye).
     */
    fun downloadAndInstall(
        ctx: Context,
        info: Info,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit,
        onInstallFailed: (Intent) -> Unit
    ) {
        val app = ctx.applicationContext
        readyInstallIntent = null
        Thread {
            try {
                val dir = File(app.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "Train.apk")

                val conn = URL(info.url).openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("User-Agent", "Train")
                    val code = conn.responseCode
                    if (code !in 200..299) throw Exception("HTTP $code")
                    val total = conn.contentLengthLong
                    var done = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        main.post { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                if (file.length() <= 0L) throw Exception("Download khali aaya")
                main.post {
                    onProgress(100)
                    install(app, file, onInstallFailed)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "download nahi hua"
                main.post { onError(msg) }
            }
        }.start()
    }

    private fun install(ctx: Context, file: File, onInstallFailed: (Intent) -> Unit) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!ensureInstallPermission(ctx)) return
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            readyInstallIntent = intent
            onInstallFailed(intent)
        }
    }
}
