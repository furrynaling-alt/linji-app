package com.naling.shuai

import android.app.admin.DeviceAdminReceiver

/** 只为「我要睡觉了 → 熄屏」用的设备管理员（权限仅 force-lock） */
class AdminReceiver : DeviceAdminReceiver()
