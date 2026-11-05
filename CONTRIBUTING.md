# 参与贡献

## 开始前

- 先读 `README.md`、`CHANGELOG.md`、`docs/MAINTENANCE.md`、`docs/DEVLOG.md` 和 `AGENTS.md`。
- 目标 App 适配依赖无障碍节点和播放器界面。不要为了让功能“看起来成功”而放宽倒计时、季集或 VPN 分流约束。
- 安全问题不要开公开 Issue，按 `SECURITY.md` 报告。

## 开发环境

- JDK 17 或更高版本
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleDebug
```

项目目前没有单元测试或 UI 自动化测试。涉及无障碍、VPN、季集状态机的改动必须做真机回归，并在 PR 中附上设备、系统、目标 App 版本和关键日志。

## 必须保持的约束

- 倒计时未结束前不得自动点击跳过、自动上滑或按 BACK 离开。
- 广告页不得污染当前剧名/季数；不得跨季自动选集；季号未确认时停止自动恢复。
- VPN 只接管已勾选的重点应用；没有目标应用时隧道只能空闲，不能回退到全设备接管。
- 切换重点应用后必须停止旧 VPN，并以新范围重启。
- IPv6 只在上游确有全局 IPv6 时接管。
- 自动学习只自动屏蔽标签明确像广告 SDK 的域名，其余进入待确认。

## 提交前检查

```bash
./gradlew :app:assembleDebug
git diff --check
git status
```

- 行为变化更新 `CHANGELOG.md`。
- 真机证据和未完成项更新 `docs/DEVLOG.md`。
- 不提交 `local.properties`、`build/`、`.gradle/`、APK、日志、截图、keystore 或 `keystore.properties`。
- 一个 PR 尽量只做一件事，不要混入无关重构。

## 签名

公开分发应使用固定的 release keystore，避免不同机器构建的 APK 签名不一致。配置方式见 `README.md` 的“正式签名（可选）”。`keystore.properties.example` 只是模板，真实密码和 keystore 文件不要提交。
