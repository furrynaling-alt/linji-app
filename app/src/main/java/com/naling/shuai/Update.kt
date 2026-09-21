package com.naling.shuai

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 棂记 v2.17 · 检查版本 + 应用内更新（2026-09-21 纳棂要求）
 *  清单（HTTPS）：{"versionCode":27,"versionName":"2.17","note":"...","url":"...apk","md5":"..."}
 *  流程：比对 versionCode → 提示 → 下载到私有目录 → 交给系统安装器
 *  ⚠️ 不用 androidx（本 App 零依赖）→ 自己写一个极简 ContentProvider 提供 content:// 给安装器，
 *     否则 API 24+ 传 file:// 会抛 FileUriExposedException
 */
object Update {

    /** 更新清单地址（跟着设置里的服务器地址走；留空 = 默认棂冕服务器） */
    fun manifest(c: Context): String = Store.serverBase() + "/linji/version.json"

    class Info(val versionCode: Int, val versionName: String, val note: String,
               val url: String, val md5: String)

    fun curVersionCode(c: Context): Int = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }

    fun curVersionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** 拉清单。网络/解析失败返回 null（调用方提示"检查失败"） */
    fun check(ctx: Context): Info? = try {
        val conn = (URL(manifest(ctx)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "Linji/${curVersionName(ctx)}")
        }
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(txt)
        Info(o.optInt("versionCode"), o.optString("versionName"),
            o.optString("note"), o.optString("url"), o.optString("md5"))
    } catch (_: Exception) {
        null
    }

    /** 下载 APK 到私有目录，返回文件（失败 null） */
    fun download(ctx: Context, info: Info, onPct: (Int) -> Unit): File? = try {
        val dir = File(ctx.filesDir, "update").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 20000
        }
        conn.inputStream.use { ins ->
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                var total = 0L
                val len = conn.contentLengthLong.coerceAtLeast(1L)
                while (ins.read(buf).also { n = it } > 0) {
                    fos.write(buf, 0, n); total += n
                    onPct(((total * 100) / len).toInt())
                }
            }
        }
        out
    } catch (_: Exception) {
        null
    }

    /** 校验 md5（清单里给了才校验） */
    fun md5Ok(f: File, expect: String): Boolean {
        if (expect.isBlank()) return true
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val d = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            d.equals(expect, true)
        } catch (_: Exception) {
            false
        }
    }

    /** 交给系统安装器（先引导"允许安装未知应用"） */
    fun install(act: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${act.packageName}")))
            android.widget.Toast.makeText(act, "请先允许「安装未知应用」，再点一次更新", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val uri = Uri.parse("content://${act.packageName}.apk/update.apk")
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        act.startActivity(i)
    }
}

/** 极简 APK 提供者：把私有目录里的 update.apk 以 content:// 暴露给安装器 */
class ApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = File(requireNotNull(context).filesDir, "update/update.apk")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
