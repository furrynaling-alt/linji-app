package com.naling.shuai

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 录像 / 录音文件存取（Android 10+ 走 MediaStore：视频进相册、录音进「音乐」） */
object RecStore {
    const val ALBUM = "棂打卡"

    class Item(val uri: Uri, val name: String, val size: Long, val date: Long, val id: Long, val audio: Boolean)

    private fun fileName(audio: Boolean): String {
        val ext = if (audio) "m4a" else "mp4"
        return "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + "." + ext
    }

    /** 返回 (uri, MediaRecorder 可用的输出对象) */
    fun newOutput(c: Context, audioOnly: Boolean): Pair<Uri, Any> {
        val name = fileName(audioOnly)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (audioOnly) "audio/mp4" else "video/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + ALBUM
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = if (audioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = c.contentResolver.insert(coll, values)
            if (uri != null) {
                val pfd = c.contentResolver.openFileDescriptor(uri, "w")
                if (pfd != null) return Pair(uri, pfd)
            }
        }
        val dir = File(c.getExternalFilesDir(null), ALBUM)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        return Pair(Uri.fromFile(f), f)
    }

    fun finish(c: Context, uri: Uri, audioOnly: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val coll = if (audioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                c.contentResolver.update(uri, v, null, null)
                // 兜底：相对路径写法在不同厂商上偶发失败，这里再按 _id 更新一次
                val id = try {
                    uri.lastPathSegment!!.toLong()
                } catch (_: Exception) {
                    -1L
                }
                if (id > 0) c.contentResolver.update(
                    Uri.withAppendedPath(coll, id.toString()), v, null, null
                )
            } catch (_: Exception) {
            }
        }
    }

    fun list(c: Context): MutableList<Item> {
        val out = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 29) {
            queryColl(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, false, out)
            queryColl(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, out)
            out.sortByDescending { it.date }
        } else {
            File(c.getExternalFilesDir(null), ALBUM).listFiles()
                ?.sortedByDescending { it.lastModified() }?.forEach {
                    out.add(Item(Uri.fromFile(it), it.name, it.length(), it.lastModified(), 0, it.name.endsWith(".m4a")))
                }
        }
        return out
    }

    private fun queryColl(c: Context, coll: Uri, audio: Boolean, out: MutableList<Item>) {
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED
            )
            c.contentResolver.query(
                coll, proj, MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf("%" + ALBUM + "%"),
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    out.add(
                        Item(
                            Uri.withAppendedPath(coll, id.toString()),
                            cur.getString(1), cur.getLong(2), cur.getLong(3) * 1000, id, audio
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    fun delete(c: Context, item: Item) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && item.id > 0L) c.contentResolver.delete(item.uri, null, null)
            else File(item.uri.path ?: "").delete()
        } catch (_: Exception) {
        }
    }

    fun humanSize(b: Long): String =
        if (b > 1024 * 1024) "%.1f MB".format(b / 1048576.0) else "%d KB".format(b / 1024)
}

/**
 * 熄屏录像 / 录音，三级降级：
 *  ① 显式配置（**严格按官方状态机顺序**：setCamera→音频源→视频源→setOutputFormat→编码器→尺寸/帧率→输出文件→prepare）
 *  ② CamcorderProfile 一键配置（厂商参数不一致时最稳）
 *  ③ 只录音（不碰摄像头）
 * 每一步失败原因都写进 App 的「上次录像日志」。
 */
class RecorderService : Service() {

    companion object {
        const val NOTI_ID = 103
        const val EXTRA_FRONT = "front"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_AUDIO_ONLY = "audio_only"

        @Volatile
        var running = false
            private set

        fun start(c: Context, front: Boolean, minutes: Int, audioOnly: Boolean = false) {
            val i = Intent(c, RecorderService::class.java)
                .putExtra(EXTRA_FRONT, front).putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) {
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, RecorderService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null
    private var outUri: Uri? = null
    private var outPfd: ParcelFileDescriptor? = null
    private var audioOnlyNow = false
    private var endAt = 0L
    private val h = Handler(Looper.getMainLooper())
    private val log = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun findCamera(front: Boolean): Int {
        val want = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        for (i in 0 until Camera.getNumberOfCameras()) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == want) return i
        }
        return 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        if (running) return START_NOT_STICKY
        val front = intent?.getBooleanExtra(EXTRA_FRONT, true) ?: true
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 30) ?: 30
        val wantAudioOnly = intent?.getBooleanExtra(EXTRA_AUDIO_ONLY, false) ?: false

        log.setLength(0)
        log.append("机型 ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.SDK_INT}\n")
        log.append("摄像头 ${Camera.getNumberOfCameras()} 个，用${if (front) "前置" else "后置"}\n")

        var ok = false
        if (!wantAudioOnly) {
            ok = attemptExplicit(front)
            if (!ok) {
                log.append("→ ① 显式配置失败，改用 CamcorderProfile 一键配置\n")
                ok = attemptProfile(front)
            }
            if (!ok) log.append("→ ② 仍失败，降级为「只录音」\n")
        }
        if (!ok) ok = attemptAudio()

        if (ok) {
            running = true
            endAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
            if (endAt > 0) h.postDelayed({ stopSelf() }, minutes * 60_000L)
            log.append("✅ 已开始录制")
        } else {
            log.append("❌ 三种方式都失败了")
            Store.setRecLog(log.toString())
            Notify.show(
                this, 94,
                Notify.build(
                    this, Notify.CH_LOCK, "录像启动失败",
                    "去「设置 → 熄屏录像」看「上次录像日志」（可一键复制发作者）"
                )
            )
            stopSelf()
            return START_NOT_STICKY
        }
        Store.setRecLog(log.toString())
        return START_NOT_STICKY
    }

    private fun foreground(audio: Boolean) {
        val noti = Notify.build(
            this, Notify.CH_LOCK, if (audio) "正在录音 ●" else "正在熄屏录像 ●",
            "换/关屏幕都不影响；回 App 点「停止录像」结束",
            ongoing = true, pi = Notify.pi(this, MainActivity::class.java, 18)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (audio) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                (android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
            startForeground(NOTI_ID, noti, type)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    /** ① 显式配置（顺序 = 官方状态机要求） */
    private fun attemptExplicit(front: Boolean): Boolean {
        var step = "startForeground"
        try {
            foreground(false)
            audioOnlyNow = false
            step = "新建输出文件"
            val (uri, sink) = RecStore.newOutput(this, false)
            outUri = uri
            if (sink is ParcelFileDescriptor) outPfd = sink

            step = "打开摄像头"
            val id = findCamera(front)
            val cam = Camera.open(id)
            camera = cam
            log.append("摄像头 id=$id 打开成功\n")
            cam.unlock()

            step = "创建 MediaRecorder"
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            recorder = r

            step = "setCamera"
            r.setCamera(cam)
            step = "setAudioSource"
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            step = "setVideoSource"
            r.setVideoSource(MediaRecorder.VideoSource.CAMERA)

            // ★ 关键：必须先设封装格式，再设编码器（之前顺序反了 → IllegalStateException）
            step = "setOutputFormat"
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            step = "setVideoEncoder"
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            step = "setAudioEncoder"
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            step = "设置视频尺寸"
            val p = cam.parameters
            val supported = p.supportedVideoSizes
            if (supported != null && supported.isNotEmpty()) {
                val best = supported.filter { it.width <= 1280 && it.height <= 720 }
                    .maxByOrNull { it.width.toLong() * it.height }
                    ?: supported.minByOrNull { it.width.toLong() * it.height }
                if (best != null) {
                    r.setVideoSize(best.width, best.height)
                    log.append("视频尺寸 ${best.width}x${best.height}\n")
                }
            }

            step = "设置帧率"
            try {
                r.setVideoFrameRate(24)
            } catch (e: Exception) {
                log.append("帧率 24 不支持，用默认\n")
            }

            step = "设置方向"
            try {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(id, info)
                r.setOrientationHint(info.orientation)
            } catch (_: Exception) {
            }

            step = "设置码率"
            try {
                r.setVideoEncodingBitRate(4_000_000)
                r.setAudioEncodingBitRate(96_000)
                r.setAudioSamplingRate(44_100)
            } catch (_: Exception) {
            }

            step = "设置输出文件"
            when (val o = sink) {
                is ParcelFileDescriptor -> r.setOutputFile(o.fileDescriptor)
                is File -> r.setOutputFile(o.absolutePath)
                else -> r.setOutputFile(o.toString())
            }

            step = "prepare()"
            r.prepare()
            step = "start()"
            r.start()
            log.append("① 显式配置启动成功\n")
            return true
        } catch (e: Exception) {
            log.append("在「$step」失败：${e.javaClass.simpleName}${if (e.message != null) " / " + e.message else "（无 message）"}\n")
            releaseAll()
            return false
        }
    }

    /** ② 用系统推荐档案配置（厂商定制参数不一致时最稳） */
    private fun attemptProfile(front: Boolean): Boolean {
        var step = "startForeground"
        try {
            foreground(false)
            audioOnlyNow = false
            step = "新建输出文件"
            val (uri, sink) = RecStore.newOutput(this, false)
            outUri = uri
            if (sink is ParcelFileDescriptor) outPfd = sink

            step = "打开摄像头"
            val id = findCamera(front)
            val cam = Camera.open(id)
            camera = cam
            cam.unlock()

            step = "创建 MediaRecorder"
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            recorder = r
            r.setCamera(cam)

            step = "应用 CamcorderProfile"
            val quality = if (CamcorderProfile.hasProfile(id, CamcorderProfile.QUALITY_720P))
                CamcorderProfile.QUALITY_720P else CamcorderProfile.QUALITY_HIGH
            r.setProfile(CamcorderProfile.get(id, quality))
            log.append("使用 CamcorderProfile quality=$quality\n")

            step = "设置输出文件"
            when (val o = sink) {
                is ParcelFileDescriptor -> r.setOutputFile(o.fileDescriptor)
                is File -> r.setOutputFile(o.absolutePath)
                else -> r.setOutputFile(o.toString())
            }
            step = "prepare()"
            r.prepare()
            step = "start()"
            r.start()
            log.append("② CamcorderProfile 启动成功\n")
            return true
        } catch (e: Exception) {
            log.append("在「$step」失败：${e.javaClass.simpleName}${if (e.message != null) " / " + e.message else "（无 message）"}\n")
            releaseAll()
            return false
        }
    }

    /** ③ 只录音 */
    private fun attemptAudio(): Boolean {
        var step = "startForeground"
        try {
            foreground(true)
            audioOnlyNow = true
            step = "新建输出文件"
            val (uri, sink) = RecStore.newOutput(this, true)
            outUri = uri
            if (sink is ParcelFileDescriptor) outPfd = sink

            step = "创建 MediaRecorder"
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            recorder = r
            step = "setAudioSource"
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            step = "setOutputFormat"
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            step = "setAudioEncoder"
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            step = "设置输出文件"
            when (val o = sink) {
                is ParcelFileDescriptor -> r.setOutputFile(o.fileDescriptor)
                is File -> r.setOutputFile(o.absolutePath)
                else -> r.setOutputFile(o.toString())
            }
            step = "prepare()"
            r.prepare()
            step = "start()"
            r.start()
            log.append("③ 只录音启动成功（存到「音乐 / 棂打卡」）\n")
            return true
        } catch (e: Exception) {
            log.append("在「$step」失败：${e.javaClass.simpleName}${if (e.message != null) " / " + e.message else "（无 message）"}\n")
            releaseAll()
            return false
        }
    }

    private fun releaseAll() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            camera?.lock()
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
        val uri = outUri
        val audio = audioOnlyNow
        try {
            outPfd?.close()
        } catch (_: Exception) {
        }
        outPfd = null
        if (uri != null) RecStore.finish(this, uri, audio)
        outUri = null
    }

    override fun onDestroy() {
        val wasRunning = running
        running = false
        h.removeCallbacksAndMessages(null)
        releaseAll()
        if (wasRunning) {
            val last = RecStore.list(this).firstOrNull()
            Notify.show(
                this, 93,
                Notify.build(
                    this, Notify.CH_LOCK, if (audioOnlyNow) "录音已保存 ✅" else "录像已保存 ✅",
                    if (last != null) last.name + " · " + RecStore.humanSize(last.size)
                    else "已保存到「${if (audioOnlyNow) "音乐" else "相册"} / 棂打卡」",
                    ongoing = false
                )
            )
        }
        super.onDestroy()
    }
}
