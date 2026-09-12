<div align="center">

<img src="docs/icon.svg" alt="TipIsMine" width="96"/>

# TipIsMine

[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](LICENSE)

**[English (English)](README.md)**

</div>

夺回 Android SystemUI 里那条 **"此设备归…所有，已通过…连接到互联网"** VPN 归属提示的控制权。想显示的部分显示、想隐藏的部分隐藏，或者整句替换成你自己的文案——TipIsMine 是一个 LSPosed / Xposed 模块，让这条横幅完全由你掌控。

## 快速开始

1. 安装与你的框架匹配的 APK（见下方「该装哪个 APK」）。
2. 在 LSPosed 中启用模块，作用域勾选 `com.android.systemui`。
3. 打开模块配置页，设置你的归属 / VPN 文案。
4. 重启 SystemUI（或重启手机）。

改完配置即生效，无需每次重启。

## 该装哪个 APK

- **modern** —— 适用于新版 LSPosed（libxposed API 102），Android 8+。大多数用户推荐。
- **legacy** —— 适用于老版 Xposed / 老版 LSPosed，Android 7+。

两者包名相同，只启用其中一个即可。

## 它能做什么

- **逐槽位控制** —— 归属和 VPN 相互独立：各自可强制**显示**、**隐藏**，或保持系统**默认**。
- **整句替换** —— 用自定义文案整段替换提示内容（优先级最高）。
- **实时预览** —— 配置页实时预览效果。
- **动态内容** —— 文案中可嵌入占位符，运行时动态求值：电量（battery）、是否充电（charging）、WiFi 名（wifi）、蓝牙设备名（bt）、当前时间（time）、当前日期（date），以及网络请求函数 get(url) / post(url, body)。括号、逻辑与/或/非、比较、三元表达式、加法或字符串拼接均可自由嵌套。需先在设置里开启「表达式注入」，关闭时占位符原文直出、不会注入 SystemUI。
- **可选 HTTP API** —— 支持远程或脚本更新配置（详见开发者版块）。

整句替换示例：`本设备由 xx 管理，正通过 yy 连接，电量 87%`

设计上不侵入系统：从不触碰归属判定逻辑，没有 VPN 时也不凭空造提示。

## 常见问题

- **顶部横幅显示红色「模块未激活」** —— 模块没有在 LSPosed 中激活；检查作用域是否包含 `com.android.systemui`。
- **「刷新速度」框变灰** —— 你的文案里没有动态占位符，自然无需刷新。
- **四个显示选项的含义** —— 默认：跟随系统原值，系统显示就显示、不显示就不显示（可配置自定义文案但不改显隐）；显示：强制该槽位显示自定义文案；隐藏：强制该槽位不显示；跟随主页（仅锁屏页）：直接沿用主页该槽位的设置。

## 许可证

[Mozilla Public License 2.0](LICENSE)

---

# 开发者

## 构建与测试

需要 Android SDK + JDK 17。Gradle wrapper 的二进制 jar 不随仓库提交；先执行 `gradle wrapper` 一次，或直接用 Android Studio 打开。

```bash
gradle wrapper
gradle testLegacyDebugUnitTest testModernDebugUnitTest   # 快速 JVM 单元测试
gradle assembleLegacyRelease assembleModernRelease        # 两个 release APK
```

`legacy` 与 `modern` 两个 APK 由同一份源码经 product flavors 构建。单元测试为纯 JVM 测试（不依赖 Android 框架），覆盖 `OwnershipKeys`、`SlotMode`、`ComposePrompt`、`PromptRewriter`、`ExpressionEngine`。

## 实现原理

全部通过改写 SystemUI `Resources.getString` 返回的字符串实现，绝不触碰 `hasDeviceOwner` / `hasProfileOwner` 等底层归属判定。

当 `getString(int)` / `getString(int, Object[])` 被调用时，模块检查资源是否属于 `com.android.systemui`、资源名是否命中归属提示 key 集合（`OwnershipKeys`），然后按优先级决定：整句替换 → 槽位干预 → 放行系统原值。

「命中 key → 按配置得出结果」的逻辑集中在 `PromptRewriter`，双 flavor 共用；只有 hook 垫片不同：legacy 用 `XposedHelpers.findAndHookMethod`，modern 用 `framework.hook(...).setExceptionMode(PROTECTIVE).intercept(Hooker)`。任一 hook 失败都会被捕获并记入日志，不崩 SystemUI。

## 网络 API（可选）

开启后模块在本机运行一个轻量 HTTP 服务器。

**端点：** `POST /config`

**请求头：** `Content-Type: application/json`、`Authorization: Bearer <api_key>`（`api_key` 为空则免认证）

**Body：** JSON，支持键：`owner_mode`、`owner_text`、`vpn_mode`、`vpn_text`、`rewrite_whole`、`whole_text`、`expose_api`、`api_port`、`api_key`、`refresh_interval`。

```bash
curl -X POST http://<手机IP>:8080/config \
  -H "Content-Type: application/json" \
  -d '{"owner_mode":"show","owner_text":"公司A","vpn_mode":"default"}'
```