package com.naling.shuai

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku：借无线调试拿到的 shell 权限执行命令。
 * 用途：执行 `input keyevent 26` = 真·按电源键 → 只熄屏、不强制锁屏（是否要密码由系统「自动锁定」决定）。
 * 注意：Shizuku 13.x 把 newProcess 设成了 private，这里用反射调用。
 */
object ShizukuShell {

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 执行一条 shell 命令，返回是否成功（不抛异常） */
    fun run(cmd: String): Boolean {
        if (!granted()) return false
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) ?: return false
            try {
                val wf = proc.javaClass.getMethod("waitFor")
                wf.invoke(proc)
            } catch (_: Throwable) {
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 按一次电源键（熄屏） */
    fun screenOff(): Boolean = run("input keyevent 26")

    /** 熄屏（电源键）并同时把系统超时压短，双保险 */
    fun screenOffPlus(): Boolean = run("input keyevent 26; settings put system screen_off_timeout 1000")
}
