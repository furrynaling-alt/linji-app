package com.naling.shuai

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

object SshShell {

    fun run(c: Context, host: String, port: Int, user: String, cmd: String, cb: (String) -> Unit) {
        Thread {
            val pem = try {
                SshKey.privatePem(c)
            } catch (_: Exception) {
                ""
            }
            if (pem.isBlank()) {
                cb("还没有私钥：先点「生成 .ssh 私钥」")
                return@Thread
            }

            var last = ""
            for (attempt in 1..3) {
                val r = once(pem, host, port, user, cmd)
                if (r != null) {
                    cb(r)
                    return@Thread
                }
                last = errMsg
                try {
                    Thread.sleep(1500)
                } catch (_: Exception) {
                }
            }
            cb(
                "连了 3 次都没成：$last\n\n" +
                        "这一步是「登录成功后打开执行通道」被网络掐断（不是钥匙问题，日志里能看到你手机已经认证成功过）。\n" +
                        "① 再点一次「配对连接」（国内直连国外高端口，经常第二次就成）\n" +
                        "② 换台服务器试：港机 185.216.118.103:16816\n" +
                        "③ 切 WiFi / 换 4G\n" +
                        "④ 只是想「手机↔服务器同步」的话用上面的 App 桥（走 HTTPS/443，比 SSH 稳得多）\n" +
                        "Termux 备用：ssh -p $port $user@$host"
            )
        }.start()
    }

    @Volatile
    private var errMsg: String = ""

    /** 连一次（会话 + 执行通道 + 读输出）；成功返回输出，失败返回 null 并把原因写进 errMsg */
    private fun once(pem: String, host: String, port: Int, user: String, cmd: String): String? {
        var s: Session? = null
        var ch: ChannelExec? = null
        return try {
            val jsch = JSch()
            jsch.addIdentity("linji", pem.toByteArray(Charsets.UTF_8), null, null)
            s = jsch.getSession(user, host, port)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "publickey")
            s.connect(15000)
            s.setTimeout(25000)

            ch = s.openChannel("exec") as ChannelExec
            ch.setCommand(cmd)
            val err = ByteArrayOutputStream()
            ch.setErrStream(err)
            val ins = ch.inputStream
            ch.connect(20000)

            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 20000L
            var idle = 0L
            while (System.currentTimeMillis() < deadline) {
                val av = try {
                    ins.available()
                } catch (_: Exception) {
                    0
                }
                if (av > 0) {
                    val n = try {
                        ins.read(buf)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n < 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    idle = System.currentTimeMillis()
                } else {
                    if (ch.isClosed) break
                    if (idle > 0 && System.currentTimeMillis() - idle > 1200L) break
                    Thread.sleep(120)
                }
            }
            val out = sb.toString()
            val es = err.toString("UTF-8")
            val all = (out + (if (es.isBlank()) "" else "\n[stderr]\n" + es)).trim()
            if (all.isNotBlank()) all else "连上了，但没读到输出\n命令：$cmd"
        } catch (e: Exception) {
            errMsg = e.message ?: e.javaClass.simpleName
            null
        } finally {
            try {
                ch?.disconnect()
            } catch (_: Exception) {
            }
            try {
                s?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
