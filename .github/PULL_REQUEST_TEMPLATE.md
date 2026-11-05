## 变更

<!-- 说明改了什么，以及为什么这样改。 -->

## 验证

- [ ] `./gradlew :app:assembleDebug`
- [ ] 涉及无障碍、VPN 或季集状态机时已完成真机回归
- [ ] 已更新 `CHANGELOG.md`
- [ ] 已更新 `docs/DEVLOG.md`（如有真机证据或未完成项）

## 安全约束

- [ ] 未在倒计时结束前自动点击跳过、上滑或 BACK
- [ ] 未跨季自动选集，未污染广告页剧名/季数
- [ ] VPN 仍只接管已勾选的重点应用
- [ ] 未提交 `local.properties`、APK、日志、截图、keystore 或 `keystore.properties`
