# AGENTS.md

本文件约束在本仓库内工作的编码代理。开始修改前先读 `README.md`、`CHANGELOG.md`、`docs/MAINTENANCE.md` 和 `docs/DEVLOG.md`。

## 项目定位

- 这是一个免 Root 的 Android 广告处理工具，重点适配红果短剧、红果漫剧和番茄短剧。
- 主要代码是 Java 17，`minSdk 26`、`targetSdk 35`、`arm64-v8a`。
- 不引入第三方中转服务器，不解密 HTTPS，不上传界面内容或用户数据。

## 不可破坏的行为约束

- 倒计时未结束前禁止自动点击跳过、自动上滑或 BACK 离开。
- 广告页不得污染当前剧名/季数；不得跨季自动选集；季号未确认时停止自动恢复。
- VPN 默认只接管已勾选的重点应用；没有目标应用时只能让隧道空闲，不能回退到全设备接管。
- 切换重点应用后必须让旧 VPN 停止、新 VPN 以新范围重启。
- IPv6 只在上游确有全局 IPv6 时接管。
- 自动学习保持保守：只自动屏蔽标签明确像广告 SDK 的域名，其余进入待确认。

详细说明见 `docs/MAINTENANCE.md`。

## 构建与真机验证

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleDebug

ADB="$HOME/Android/Sdk/platform-tools/adb"
"$ADB" devices -l
```

有设备时，至少验证：

- 安装后无障碍服务已连接，VPN 日志显示 `tunnel apps=N`。
- 切换重点应用会触发 `ad filter stopped` 和新的 `tunnel apps=N`。
- 广告域名出现 `blocked` 日志。
- 倒计时期间没有自动点击、上滑或 BACK。
- 如改动季集状态机，必须用真实剧集广告回归，不能只做静态判断。

设备环境不固定时，先运行 `tools/device-check.sh`。不要为了通过检查而降低倒计时或季集安全约束。

## 修改流程

- 行为变化必须更新 `CHANGELOG.md`；真机证据和未完成项更新到 `docs/DEVLOG.md`。
- 发布新版本时同步修改 `app/build.gradle` 的 `versionCode/versionName`、`MainActivity` 底部版本号、`README.md` 版本/安装路径。
- 复制 APK 到 `dist/` 后重新计算 SHA-256，并更新 `README.md` 与 `dist/SHA256SUMS`。
- 对外分发使用 GitHub Releases；仓库只保留 `dist/SHA256SUMS` 和 `dist/README.md`，APK 不提交 Git。正式版本使用固定 release keystore，避免不同机器或 CI 的签名不一致。
- 不提交 `local.properties`、`build/`、`.gradle/`、APK、临时日志或截图。
- 不要无理由增加依赖；APK 已包含 ML Kit OCR 和 native tun2socks 库，体积约 28MB。

## 代码风格

- 保持现有 Java 风格，最小化改动，不把无关重构混进功能修复。
- 不添加无必要注释；关键状态机分支用简洁日志说明原因。
- 目标 App 适配失败时，宁可明确标记不兼容，也不要放宽安全约束来“让它看起来成功”。
