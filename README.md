# 棂记（Linji）

一个自用的安卓工具 App：**打卡 / 锁机与睡眠 / 番茄钟 / 记录 / 用药 / 工时工资 / 记账 / 统计**，
纯 Kotlin + 纯代码布局，**零 AndroidX、零第三方 UI 库**（只有熄屏用的 Shizuku 一个依赖），
编译出来约 2 MB，没有广告、没有账号、没有云。

> 服务器端（更新清单 + App 桥）在另一个仓库：**linji** —— 那份里是 `bridge.php` / `server.js` / nginx 配置 / 部署教程。

---

## 功能

| 模块 | 说明 |
|---|---|
| 首页卡片 | 纪念日/倒数日卡片网格（可加删改、换背景、调高度、单格/半宽/整行、图表卡） |
| 打卡习惯 | 习惯列表 + 近 7 天完成率 |
| 番茄钟 | 预设可增删、后台横幅、专注期间可锁机 |
| 记录 | 随手记 + **待办**（服务器可以往手机加待办，点一下即完成） |
| 用药 | 用药提醒 + 买药记录 |
| 工时工资 | 小时工记账：记一笔 / 明细 / 日历 / 班次统计 / 记月工资 |
| 记账 | 收支、分类、预算、统计、CSV 导出 |
| 统计 | 月度历史 + 折线/柱状/饼图/表格 |
| **锁机与睡眠** | 到点询问「我要睡觉了 / 晚 N 分钟」→ 熄屏 → 睡眠计时 → **4:00 后可点「早上好」** → 记录这一觉（近 7 次平均） |
| 熄屏录像 | 关屏继续录（只录自己的场景用） |
| 备份 | 导出/导入 **`.linji` 加密备份**（AES-256-GCM + PBKDF2 20 万次）；兼容旧 `.json` |
| 应用内更新 | 服务器放 `version.json` + APK，App 里点「检查更新」直接装 |
| App 桥（可选） | 每 30 秒上报状态（电量/睡眠/待办/卡片），服务器可下发：通知、加待办、改睡觉时间、锁机… |
| SSH 密钥 | App 内生成 RSA-2048（公钥贴到服务器 `authorized_keys` 用） |

## 编译

环境：**JDK 17** + **Android SDK Platform 34 / Build-Tools 34.0.0**（用自带的 Gradle wrapper 8.7）

```bash
git clone https://github.com/furrynaling-alt/linji-app.git
cd linji-app
echo "sdk.dir=/你的/android-sdk" > local.properties
export JAVA_HOME=/你的/jdk17
./gradlew :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 直接 File → Open 打开这个目录也行。

## 签名（必读）

**本仓库不含签名钥匙**（钥匙在作者机器上，不公开），所以直接编出来的是**系统默认 debug 签名**的包 —— 自己用完全没问题。

想出自己的正式包：

```bash
keytool -genkeypair -keystore app/shuai.keystore -alias 你的别名 -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass 你的密码 -keypass 你的密码 -dname "CN=you, C=CN"
printf 'storePassword=你的密码\nkeyAlias=你的别名\nkeyPassword=你的密码\n' > app/keystore.properties
```

⚠️ **签名换了就不能覆盖安装**（安卓会报签名不一致）→ 先导出备份、卸载旧版、装新版、再导入备份。
⚠️ 反过来也成立：**拿到别人的钥匙 = 能签出"别人的"升级包去覆盖他的手机**，所以钥匙永远不要外发。

## 权限都用来干嘛

| 权限 | 用途 |
|---|---|
| 悬浮窗（SYSTEM_ALERT_WINDOW） | 锁机全屏界面、黑屏兜底 |
| 通知 | 锁机前台服务、提醒、桥下发的通知 |
| 电池优化白名单 / 自启动 | 夜里锁机不被系统杀掉（国产 ROM 必开） |
| 精确闹钟 | 到点锁机、贪睡、用药提醒 |
| 摄像头 / 麦克风 | **只有**「熄屏录像」用，且必须手动点开始 |
| 写系统设置（WRITE_SETTINGS） | 熄屏用（把超时临时调短），用完还原 |
| 无线调试（Shizuku） | 真·按电源键熄屏（可选，不装也能用其它方式熄屏） |

## 隐私

- 数据全部在**手机本地**（`SharedPreferences`），不联网也能用全部功能。
- 唯一会联网的地方：①「检查更新」②**你自己打开的**「App 桥」。桥的地址和令牌都由你填，关掉就完全离线。
- `.linji` 备份是**本地加密**的，口令不落盘、不上传。

## 已知坑（作者真机踩过的）

- **熄屏 ≠ 锁屏**：用的是「放开常亮 + 临时调短系统超时」，绝不用 `DevicePolicyManager.lockNow()`（那会要求输密码）。部分国产 ROM 不理会系统超时 → 有「黑屏兜底」（把窗口亮度调 0）。
- 夜里进程被系统杀掉会丢计时 → 15 秒心跳 + 早上自动补记一觉。
- 「早上好」按钮**必须常驻创建**（用 `GONE` 藏），到点只切 `visibility`；如果创建时按"还没到 4 点"决定不建，到点就永远不出现（真踩过）。

## 许可

MIT License，见 `LICENSE`。
