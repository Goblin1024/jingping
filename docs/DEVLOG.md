# 开发日志

按时间倒序记录真机行为、验证证据和未完成事项。版本级摘要见根目录 `CHANGELOG.md`。

## 2026-11-05 — 1.1.8 稳定性与广告域名学习

### 背景

用户报告三类问题：

1. 第一季播放广告后会自动跳到第七季，普通滑动逻辑无法解释。
2. 强制倒计时广告不结束就无法滑动到下一集。
3. 怀疑本地过滤 VPN 是否真的工作，以及是否必须提前维护完整广告域名。

### 本轮处理

- 倒计时期间禁止自动上滑、提前跳过和 BACK 退出，倒计时结束后才允许执行广告离开逻辑。
- VPN 从全设备接管改为按应用分流；无目标应用时隧道只允许自身，不再影响浏览器和非目标应用。
- 重点应用切换增加防抖和自动停止/重启，确保 allowed-app 范围生效。
- 增加 `DnsQueryLog`、`AdDomainLearner` 和学习记录界面。
- IPv6 改为按上游网络能力决定是否接管。
- 更新 README 的工作方式、边界、安装说明和 APK 哈希。
- 建立 `CHANGELOG.md`、`docs/MAINTENANCE.md`、本开发日志、`AGENTS.md` 和 `tools/device-check.sh`，作为长期维护基线。

### 真机证据

设备：V2453A / Android 16 / `PD2453D_A_16.1.18.1.W10`。

倒计时保护：

```text
17:45:37 detected count=3
17:45:38 detected count=2
17:45:39 detected count=1
```

上述期间没有点击跳过、没有自动上滑、没有按 BACK。

自动学习：

```text
17:45:37 JingPingLearn learned auto=2
          ads3-normal-lq.zijieapi.com, ads3-normal.zijieapi.com
17:46:23 learned p3-ad-sign.byteimg.com
17:46:50 blocked p3-ad-sign.byteimg.com
17:46:51 blocked ads3-normal-lq.zijieapi.com
```

按应用 VPN 和切换重启：

```text
18:00:29 JingPingDns ad filter stopped
18:00:30 JingPingProxy local SOCKS5 listening on 39103
18:00:30 JingPingDns tunnel apps=2
18:00:30 JingPingDns ad filter started, rules=99, dns=6
18:00:54 JingPingDns ad filter stopped
18:00:54 JingPingDns tunnel apps=3
18:00:54 JingPingDns ad filter started, rules=99, dns=6
```

其他验证：

- 红果漫剧第 70 集播放期间出现 `blocked ads3-normal.zijieapi.com`、`blocked ads5-normal.zijieapi.com`。
- 学习记录界面显示 3 个已自动屏蔽域名，均带“取消”按钮，待确认数量为 0。
- 当前 IPv4-only 网络启动日志为 `tunnel ipv6=disabled`。

### 未完成

- [ ] 用用户实际剧名在第一季触发广告，确认不会再跳到第七季；必须记录剧名、季号、集数、广告标记和 force-skip 拒绝日志。
- [ ] 让一个新广告域名先漏过一次，验证“待确认 -> 手动确认 -> 下次阻断”的完整链路。
- [ ] 验证 DoH/QUIC、直接 IP、共用域名三类漏网的实际情况。
- [ ] 观察 24 小时耗电、首包延迟，以及红果是否出现 VPN 网络受限提示。
- [ ] 发下个版本时修复 `MainActivity` 底部版本号硬编码。

### 设计决定

- 不追求“全设备 VPN + 全量规则”，优先保证非目标应用不受影响。
- 不把提前跳过倒计时当作功能；无 Root 只能等待或遮挡。
- 自动学习保持保守，宁可把可疑域名放进待确认，也不自动误杀视频 CDN。
- 季集状态机的修复必须用真实广告回归，不能只靠静态阅读代码。

## 2026-11-04 — 精确选集与红包挂件适配

### 真机验证

- 在《聚宝仙盆之杂灵根才是真BOSS第三季》第 86 至 107 集之间多次触发插页广告；普通广告页只上滑一次，剧集只前进一集。
- 精确选集实测：第 32、34、37 集广告后分别从选集进入第 33、35、38 集片头。
- 临时调试入口从第 35 集触发同一状态机，确认只前进到第 36 集；调试入口已从交付包移除。
- 红果漫剧 `7.4.0.32` 金币红包挂件测试：暂停后露出 `com.kylin.read:id/eyr`，点击 X 后浮层消失，没有进入福利页，也没有触发领取；冷启动后未恢复。

### 已知风险

- 红果/番茄升级后界面节点变化会让精确选集或红包关闭失效。
- 空白广告兜底依赖“稳定集数 + 5 秒黑屏”，规则过宽会误翻正常加载页，过窄会漏掉真实空白广告。
