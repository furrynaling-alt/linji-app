package com.naling.shuai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class QuickWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(context))
    }

    private fun build(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_main)
        v.setOnClickPendingIntent(R.id.wSleep, pi(c, 1))
        v.setOnClickPendingIntent(R.id.wLock, pi(c, 2))
        v.setOnClickPendingIntent(R.id.wPomo, pi(c, 3))
        v.setOnClickPendingIntent(R.id.wBook, pi(c, 4))
        v.setOnClickPendingIntent(R.id.wOpen, pi(c, 5))
        return v
    }

    private fun pi(c: Context, what: Int): PendingIntent {
        val i = Intent(c, WidgetAction::class.java).putExtra(WidgetAction.EXTRA, what)
        return PendingIntent.getBroadcast(
            c, what, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
