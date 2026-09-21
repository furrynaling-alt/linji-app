package com.naling.shuai

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

object SshShell {

    fun run(c: Context, host: String, port: Int, user: String, cmd: String, cb: (String) -> Unit) {
        Thread {
            try {
                val pem = SshKey.privatePem(c)
                if (pem.isBlank()) {
                    cb("还没有私钥：先点「生成 .ssh 私钥」")
                    return@Thread
                }

                var sess: Session? = null
                var last: String = ""
                for (attempt in 1..3) {
                    try {
                        val jsch = JSch()
                        jsch.addIdentity("linji", pem.toByteArray(Charsets.UTF_8), null, null)
                        val s = jsch.getSession(user, host, port)
                        s.setConfig("StrictHostKeyChecking", "no")
                        s.setConfig("PreferredAuthentications", "publickey")
                        s.connect(12000)
                        s.setTimeout(15000)
                        sess = s
                        break
                    } catch (e: Exception) {
                        last = e.message ?: e.javaClass.simpleName
                        try {
                            Thread.sleep(1200)
                        } catch (_: Exception) {
                        }
                    }
                }
                val s = sess
                if (s == null) {
                    cb(
                        "连接失败（重试 3 次都没成）：$last\n\n" +
                                "国内直连国外服务器的 SSH 端口偶尔会被丢包，常见是\"第一次失败、第二次成功\"。\n" +
                                "① 隔几秒再点一次「配对连接」\n" +
                                "② 换个端口/换台服务器试（例：港机 185.216.118.103:16816）\n" +
                                "③ 手机切 WiFi / 换 4G 试\n" +
                                "Termux 备用：ssh -p $port $user@$host"
                    )
                    return@Thread
                }

                var ch: ChannelExec? = null
                try {
                    ch = s.openChannel("exec") as ChannelExec
                    ch.setCommand(cmd)
                    val err = ByteArrayOutputStream()
                    ch.setErrStream(err)
                    val ins = ch.inputStream
                    ch.connect(12000)

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
                    cb(if (all.isNotBlank()) all else "连上了，但没读到输出\n命令：$cmd")
                } finally {
                    try {
                        ch?.disconnect()
                    } catch (_: Exception) {
                    }
                    try {
                        s.disconnect()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                cb("出错：" + (e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }
}
