# 长期维护手册

适用版本：`1.1.8`（`versionCode 11`）。本文件面向后续开发和真机调试，不面向普通用户。

## 当前基线

| 项目 | 当前值 |
| --- | --- |
| 应用 ID | `com.pure.jingping` |
| 最低系统 | Android 8.0（API 26） |
| 编译/目标 | API 35 |
| ABI | `arm64-v8a` |
| 语言 | Java 17 |
| 构建工具 | Gradle 8.9 / AGP 8.7.3 |
| 真机 | V2453A / Android 16 / `PD2453D_A_16.1.18.1.W10` |
| 重点应用 | `com.phoenix.read`、`com.kylin.read`、`com.dragon.read` |

项目目前没有单元测试或 UI 自动化测试，交付前必须在真机上做手动回归。核心功能依赖目标 App 的无障碍节点和播放器界面，目标 App 升级后可能失效。

## 构建与安装

本机已验证 JDK 21 可以构建；源码兼容级别为 Java 17。

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

真机（本机 `adb` 不在默认 PATH）：

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb"
"$ADB" devices -l
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接运行 `tools/device-check.sh` 做一次只读环境检查。

## 运行架构

```text
MainActivity
  └─ Prefs：开关、重点应用、自定义/已学习域名、统计

AdAccessibilityService
  ├─ 无障碍节点扫描：跳过按钮、广告标记、倒计时、集数、播放器 UI
  ├─ 精确选集状态机：返回历史页 -> 选集 -> 第 N+1 集
  └─ 金币/红包挂件：暂停露出原生 X -> 永久关闭

DnsFilterVpnService
  ├─ VpnService.Builder + addAllowedApplication(重点应用)
  ├─ tun2socks Engine：TUN <-> 本机 SOCKS5
  └─ Socks5Server（127.0.0.1）
       ├─ TCP CONNECT：DNS/IP 目标先查规则；443/80 再看 TLS SNI / HTTP Host
       ├─ UDP ASSOCIATE：解析 DNS 查询并阻断命中域名
       └─ DnsQueryLog -> AdDomainLearner -> Prefs -> reloadRules()
```

数据流：

1. Android 把重点应用的流量路由到 `tun0`。
2. tun2socks 把流量交给本机 SOCKS5。
3. DNS 查询命中规则时返回失败；未命中时转发到当前网络 DNS 或回退解析器。
4. TCP 443/80 连接会短暂读取首包，检查 TLS SNI 或 HTTP Host；命中规则直接关闭。
5. 未命中连接用 `protect()` 绑定当前 Wi-Fi/蜂窝网络后转发。
6. 广告出现时，学习者读取最近 120 秒的域名记录，生成自动规则或待确认规则。

## 必须保持的行为约束

这些约束是避免误操作和用户投诉的底线，修改相关代码前先读一遍。

### 倒计时安全

- 倒计时未结束时，不得自动点击“跳过”、不得自动上滑、不得按 BACK 离开。
- 倒计时结束是执行跳过/离开逻辑的前置条件。
- 无 Root 无法篡改目标 App 的计时器；不要用“提前跳过”伪装成已经缩短倒计时。

### 季集安全

- 广告页会清空临时剧名/季数上下文，广告页上的推荐卡片不得污染当前播放季集。
- “标题没变但季数变了”的卡片变化继续按 card-only 变化忽略。
- 跨季自动选集必须拒绝；没有确认季号时停止自动恢复。
- 空白广告兜底只能在“同一稳定集数持续黑屏 5 秒且没有广告转场”时执行一次。
- 修改状态机后，必须用真实剧集广告回归“第一季广告后不能进入其他季”。

### 分流范围

- 默认只能对已勾选的重点应用调用 `addAllowedApplication`。
- 未选择应用时，隧道只允许净屏自身，不能回退到全设备接管。
- 切换重点应用后必须让旧 VPN 停止、新 VPN 以新范围重启。
- IPv6 只在上游网络确实存在全局 IPv6 时接管；否则保持 `ipv6=disabled`。

### 隐私与协议边界

- 不连接第三方 VPN/代理，不运营中转服务器。
- 不解密 HTTPS，不保存请求正文、账号信息或截图。
- 最近域名只保存在内存中，最多 600 条、最多回看 120 秒。
- 自动学习必须保守：只有标签明确像广告 SDK 的域名自动屏蔽，其余进入待确认。

## 日志与调试

### 读取日志

本机 ROM 上 `logcat -d -t N` 有时返回空，优先按 PID 或时间起点过滤：

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb"
PID=$("$ADB" shell pidof com.pure.jingping)
"$ADB" logcat -v time --pid="$PID" -d | grep -E 'JingPing|JingPingDns|JingPingProxy|JingPingLearn'
```

或：

```bash
"$ADB" logcat -v time -T '11-05 17:50:00.000' -d | grep -E 'JingPing|JingPingDns|JingPingProxy|JingPingLearn'
```

关键标签：

| 标签 | 用途 | 典型日志 |
| --- | --- | --- |
| `JingPing` | 无障碍扫描和季集状态机 | `detected ... count=3`、`force skip start` |
| `JingPingDns` | VPN 生命周期 | `tunnel apps=3`、`ad filter started` |
| `JingPingProxy` | SOCKS5/DNS/SNI 阻断 | `blocked ads3-normal.zijieapi.com` |
| `JingPingLearn` | 自动学习 | `learned auto=2` |
| `GoLog` | tun2socks 引擎 | `[TCP] ...`、`[UDP] ...` |

### 查看当前 VPN

```bash
"$ADB" shell ip addr show tun0
"$ADB" shell dumpsys connectivity | grep -i -E 'VPN|tun0' | head -40
```

期望看到 `tun0` 地址 `10.111.0.2/32`，日志中 `tunnel apps=N` 与界面勾选数量一致。

### 抓界面节点

```bash
"$ADB" shell uiautomator dump --compressed /sdcard/ui.xml
"$ADB" pull /sdcard/ui.xml /tmp/ui.xml
```

如果出现 `could not get idle state`，先确认目标 App 已在前台（`dumpsys window | grep mCurrentFocus`），再重试；必要时用 `screencap` 截图辅助确认。

### 常用验证场景

| 场景 | 操作 | 通过标准 |
| --- | --- | --- |
| 构建 | `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL` |
| 安装 | `adb install -r ...apk` | `Success` |
| VPN 范围 | 启动本地过滤 | `tunnel apps=3`，非目标 App 不走 VPN |
| 切换重点应用 | 取消/恢复一个复选框 | 自动出现 `ad filter stopped`，随后 `tunnel apps=2/3` |
| 规则阻断 | 打开红果并看日志 | 出现对应 `blocked <domain>` |
| 自动学习 | 触发一次广告后看学习记录 | 新域名进入“已自动屏蔽”或“待确认” |
| 倒计时 | 遇到强制倒计时广告 | `count=3/2/1` 期间无点击、无上滑、无 BACK |
| 季集安全 | 第一季剧集广告 | 不得跳到其他季；日志应出现 card-only/跨季拒绝保护 |
| 红包挂件 | 红果播放器出现浮层 | 识别 X、永久关闭、自动恢复播放 |

## 常见问题

### VPN 已开启但日志没有 `blocked`

- 确认目标 App 在重点应用列表中，且界面显示“本地过滤运行中”。
- 确认 `tunnel apps` 大于 0。
- 有些请求在 VPN 启动前已经建立，或走 DoH/QUIC、直接 IP；域名规则看不到。
- 用 `dumpsys connectivity` 和 `ip addr show tun0` 确认隧道仍在。

### 应用全部取消后 VPN 还在

这是预期行为：没有目标应用时隧道只允许净屏自身，保持空闲而不是接管全设备。要完全停止，点界面上的“停止本地过滤”。

### 切换重点应用后没有重启

- 确认本地过滤正在运行；未运行时切换只保存设置，不会启动 VPN。
- 连续切换会经过 700ms 防抖，等待 1 至 2 秒再看日志。
- 设备上如果 `logcat` 看不到日志，按 PID 方式抓取。

### 设置里显示无障碍未开启，但界面显示已连接

在这台 vivo 上，`settings get secure enabled_accessibility_services` 可能返回空，`dumpsys accessibility` 的 `Enabled services` 也可能为空，但 `JingPing` 日志仍持续出现 `accessibility service connected` 和 `detected ...`。不要只凭 secure 设置判断服务状态；以界面状态和日志为准。`tools/device-check.sh` 会同时报告这两类证据。

### 红果升级后功能失效

- 先用 UI dump 对比跳过按钮、选集入口、播放器控件、红包 X 的节点文本和 resource-id。
- 只改选择器/文本匹配，不要放宽倒计时安全约束。
- 如果新版彻底移除了原生关闭入口，功能只能标记为不兼容，不能假装成功。

## 发布检查清单

1. 修改 `app/build.gradle` 的 `versionCode` 和 `versionName`。
2. 同步修改 `MainActivity` 底部版本号、`README.md` 标题/下载说明/当前版本和 `dist/README.md`。
3. 运行 `./gradlew :app:assembleDebug`，确认构建成功。
4. 安装到真机，做构建、VPN 范围、切换重启、广告阻断、学习记录、倒计时安全回归。
5. 公开分发版本使用固定 release keystore 构建 `./gradlew :app:assembleRelease`；debug 包只用于测试，不要作为长期升级渠道。
6. 复制到 `dist/jingping-vX.Y.Z.apk`（debug 包保留 `-debug` 后缀），运行 `sha256sum dist/jingping-vX.Y.Z.apk`。
7. 更新 `README.md`、`dist/README.md` 和 `dist/SHA256SUMS` 中的版本、文件名和哈希。
8. 更新 `CHANGELOG.md` 和 `docs/DEVLOG.md`，记录设备、系统、目标 App 版本、日志证据和未完成项。
9. 确认 `git status` 和 GitHub Actions 构建通过；不要把 `local.properties`、构建目录、APK、日志、截图、keystore 或 `keystore.properties` 提交进仓库。
10. 提交后打 `vX.Y.Z` 标签并推送，在 GitHub Releases 上传 APK 和 `SHA256SUMS`。下载发布资产并重新校验哈希。

APK 被根目录 `.gitignore` 的 `*.apk` 忽略，二进制只通过 GitHub Releases 或其他发布存储分发；`dist/SHA256SUMS` 和 `dist/README.md` 只记录元数据。

## 维护热点

- `AdAccessibilityService.java` 约 3700 行，强依赖红果/番茄的界面文本和节点；升级适配优先在这里。
- `Socks5Server.java` 的首包检查最多等待 3.5 秒；不要把超时调得过短，否则会误判正常 TLS 连接。
- `DnsRules` 是后缀匹配，不做通配符/正则；新增规则前确认不会命中业务 CDN。
- `MainActivity` 的版本文案是硬编码，发版容易漏改。
- APK 约 28MB，主要来自 ML Kit OCR 和 `arm64-v8a` native 库；增加依赖前先评估体积。
- 没有自动化测试；任何状态机改动都应附带可复现的日志和真机验证记录。
