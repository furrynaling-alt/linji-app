package com.naling.shuai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class QuickWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(context))
    }

    companion object {

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_main)
            val last = Store.lastSleep()
            val l30 = Store.sleeps().take(30)
            val a30 = if (l30.isEmpty()) 0 else l30.sumOf { it.minutes } / l30.size
            v.setTextViewText(
                R.id.wInfo,
                if (last == null) "还没有睡眠记录（点「我要睡觉了」）"
                else "昨晚 %d 小时 %d 分 ｜ 近 30 次均 %d 小时 %d 分".format(
                    last.minutes / 60, last.minutes % 60, a30 / 60, a30 % 60
                )
            )
            v.setOnClickPendingIntent(R.id.wSleep, pi(c, 1))
            v.setOnClickPendingIntent(R.id.wLock, pi(c, 2))
            v.setOnClickPendingIntent(R.id.wPomo, pi(c, 3))
            v.setOnClickPendingIntent(R.id.wBook, pi(c, 4))
            v.setOnClickPendingIntent(R.id.wOpen, pi(c, 5))
            v.setOnClickPendingIntent(R.id.wInfo, pi(c, 5))
            return v
        }

        fun refresh(c: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(c)
                val ids = mgr.getAppWidgetIds(ComponentName(c, QuickWidget::class.java))
                if (ids == null || ids.isEmpty()) return
                for (id in ids) mgr.updateAppWidget(id, build(c))
            } catch (_: Exception) {
            }
        }

        private fun pi(c: Context, what: Int): PendingIntent {
            val i = Intent(c, WidgetAction::class.java).putExtra(WidgetAction.EXTRA, what)
            return PendingIntent.getBroadcast(
                c, what, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
