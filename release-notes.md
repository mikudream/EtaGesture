HyperOS 4 手势条双击唤出 Eta 助手浮窗（libxposed API 102 独立模块）。

## 功能

- **双击底部手势条** → 通过系统助理会话唤出 Eta 助手浮窗（与长按电源键同一条路径，不切换任务）
- **唤醒后震动反馈**（轻触 CLICK，可在设置页关闭）
- **隐藏桌面图标**（可恢复；隐藏后可用 adb 打开设置）
- 设置页开关通过 RemotePreferences 实时生效，无需重启

## 环境要求

- HyperOS 4 / Android 17（Redmi K90 Pro Max，OS4.0.6.0.XPMCNXM 实测）
- LSPosed，且框架支持 libxposed API 102
- 已安装 Eta；建议将 Eta 设为默认数字助理（否则需授予 Eta 悬浮窗权限）

## 安装

1. 安装本 APK
2. 在 LSPosed 中启用「Eta 手势」，作用域只勾选 `system`
3. 重启手机
4. 打开「Eta 手势」确认显示「LSPosed 已连接」，按需开关

## 说明

- 单击手势条不触发；三键/双键导航、锁屏状态下不判定
- 隐藏桌面图标后，重新打开设置：`adb shell am start -n io.github.mangi.etagesture/.SettingsActivity`
- 日志标签 `[EtaGesture]`，可在 LSPosed 日志中查看 `已挂钩`、`手势条双击`、`已通过助理会话唤出 Eta`
- APK 为 debug 签名，直接 sideload 安装即可
