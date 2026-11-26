# mpvRx v3.1.1 · MediaCodec embed TV 定制版

基于上游 **v3.1.1**（2026-11-23 正式版，`501f1099`），面向 Android TV 的定制构建。版本号跟随上游 `3.1.1`。

## 本 fork 的改动

1. **MediaCodec embed 默认开启** — 上游默认 `false`，我们改为 `true`，零拷贝 HW+ 输出开箱即用。设置项本身是上游原生的（设置 → 解码器 → Use MediaCodec embed），可随时手动关闭。
2. **全屏 DPAD 上下键 ±0.1 调速** — 仅在全屏播放、无面板/弹窗、未暂停时生效；每次 ±0.1，范围 0.1–4.0。提示使用系统原生 Toast，避免大白边；暂停或打开面板后上下键恢复正常遥控器导航。
3. **CI 构建** — 平时 dispatch 默认只出 fongmi v7a 薄包；本 Release 为 `v7a + arm64` 全量包。

其余代码**全部跟随上游 v3.1.1**，旧的静态 OSD、View 层 DAR、自建 AAR 等定制已刻意废弃。

## 内核与上游特性

- 内核：官方 `mpvlib-fongmi 1.1.8.8`（4 ABI，含 FongMi seek fix）
- 动态 OSD（`MpvSurfaceHost`）、`android-video-rect` 画面比例
- TV 解码器选择按钮（我们的 PR #975 已合并）
- Android 9 正则崩溃修复（#976 已合并）
- 快进 OSD、统计页 Off、浏览器默认值均已上游原生

## 实机验证

已在 TCL / 雷鸟 Android TV（Android 9，Mali-G52）验证：连续快进无卡死、画面比例正确、调速无白边、字幕与单层 Dolby Vision 正常。

## 安装提示

本包使用 CI 临时签名，与旧版签名不同，**请先卸载旧版再安装**。
