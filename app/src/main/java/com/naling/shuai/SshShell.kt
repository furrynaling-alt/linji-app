package com.naling.shuai

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

object SshShell {

    fun run(c: Context, host: String, port: Int, user: String, cmd: String, cb: (String) -> Unit) {
        Thread {
            var s: Session? = null
            try {
                val pem = SshKey.privatePem(c)
                if (pem.isBlank()) {
                    cb("还没有密钥：先在「SSH 密钥」里点一下生成，再把公钥贴到服务器")
                    return@Thread
                }
                val jsch = JSch()
                jsch.addIdentity("linji", pem.toByteArray(Charsets.UTF_8), null, null)
                s = jsch.getSession(user, host, port)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "publickey")
                s.connect(12000)
                val ch = s.openChannel("exec") as ChannelExec
                ch.setCommand(cmd)
                val err = ByteArrayOutputStream()
                ch.setErrStream(err)
                val ins = ch.inputStream
                ch.connect()
                val out = ins.bufferedReader().use { it.readText() }
                ch.disconnect()
                val es = err.toString("UTF-8")
                val all = (out + (if (es.isBlank()) "" else "\n[stderr]\n" + es)).trim()
                cb(all.ifBlank { "（命令没有输出）" })
            } catch (e: Exception) {
                cb(
                    "连接失败：" + (e.message ?: e.javaClass.simpleName) +
                            "\n\n检查：① 主机/端口 ② 服务器上 authorized_keys 有没有这把公钥 ③ 网络\n" +
                            "也可以用 Termux：ssh -p $port $user@$host"
                )
            } finally {
                try {
                    s?.disconnect()
                } catch (_: Exception) {
                }
            }
        }.start()
    }
}
