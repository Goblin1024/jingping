# 分发产物

本目录保存版本化 APK 和校验和。APK 被根目录 `.gitignore` 的 `*.apk` 忽略，不进入 Git；发布二进制请单独备份到发布存储。

| 文件 | 版本 | 构建日期 | 大小 | 说明 |
| --- | --- | --- | ---: | --- |
| `jingping-v1.0.0-debug.apk` | 1.0.0 | 2026-11-04 | 53.4K | 早期原型，仅保留产物 |
| `jingping-v1.0.1-debug.apk` | 1.0.1 | 2026-11-04 | 32.5K | 早期原型，仅保留产物 |
| `jingping-v1.1.1-debug.apk` | 1.1.1 | 2026-11-04 | 11.6M | 早期版本，详细变更未归档 |
| `jingping-v1.1.2-debug.apk` | 1.1.2 | 2026-11-04 | 11.5M | 精确选集版本 |
| `jingping-v1.1.8-debug.apk` | 1.1.8 | 2026-11-05 | 27.8M | 当前版本 |

当前交付包：

```text
dist/jingping-v1.1.8-debug.apk
SHA-256: 7dc3ea5dad0c2ded282d456b31422e0890b627cad3f61c3745a2a8b310a0e48c
```

完整校验和见 `dist/SHA256SUMS`。安装命令：

```bash
adb install -r dist/jingping-v1.1.8-debug.apk
```
