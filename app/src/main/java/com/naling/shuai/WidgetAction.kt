package com.naling.shuai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

class WidgetAction : BroadcastReceiver() {

    companion object {
        const val EXTRA = "what"
    }

    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(EXTRA, 0)) {
            1 -> {
                LockService.start(c, 0, LockService.MODE_SLEEP)
                Toast.makeText(c, "熄屏睡觉，明早点「早上好」", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                LockService.start(c, Store.quickLockMin(), LockService.MODE_PLAIN)
                Toast.makeText(c, "已锁机 ${Store.quickLockMin()} 分钟", Toast.LENGTH_SHORT).show()
            }
            3 -> open(c, 1)
            4 -> open(c, 6)
            5 -> open(c, 0)
        }
        if (!Settings.canDrawOverlays(c) && (i.getIntExtra(EXTRA, 0) == 1 || i.getIntExtra(EXTRA, 0) == 2)) {
            Toast.makeText(c, "提示：请先给棂记「悬浮窗」权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun open(c: Context, page: Int) {
        val t = Intent(c, MainActivity::class.java)
        t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        t.putExtra("page", page)
        c.startActivity(t)
    }
}
