# Eta 手势（EtaGesture）

在 HyperOS 4（Android 17，含国行小米）上，为手势导航条增加：

- **双击小白条** → 唤出 **Eta 助手浮窗**（与长按电源键唤出同一个界面）

这是一个**独立**的 libxposed API 102 模块，不修改 Eta 本体；Eta 保持官方版本即可。若希望双击时不会把 Eta 主界面所在任务带到前台，可使用把助手桥接 Activity 改为空 `taskAffinity` 独立任务的 Eta 构建（本工作区 `D:\DSH\Eta` 已包含这一行修复）。

![设置页](docs/screenshot.png)

## 功能

- 在 SystemUI 进程旁路观察输入事件，只在底部手势条区域识别双击，不消费触摸、不改变系统手势。
- 判定条件：单指、按下与抬起都在手势条区域、位移不超过触摸阈值、时长不超过长按阈值；第二下与第一下抬手的间隔、两次落点距离受系统双击阈值约束。
- 三键/双键导航、锁屏、开关关闭时不触发；多指、滑动、取消都会作废当前按压。
- 识别后向 `io.github.mangi.eta/io.github.mangi.eta.agent.voice.EtaVoiceAssistActivity` 发送 `ACTION_ASSIST`：
  Eta 是默认数字助理时由 `VoiceInteractionSession` 显示助手浮窗，否则回退到 Eta 的 `EtaAssistantOverlayService`（需要 Eta 悬浮窗权限）。
- 设置页开关实时生效（RemotePreferences），无需重启；日志标签 `[EtaGesture]`。

## 为什么在 SystemUI 里做

HyperOS 4 起手势条触摸由桌面原生手势运行时分发：`dumpsys input` 中
`[Gesture Monitor] swipe-up` 属于 `com.miui.home`，SystemUI 的 `NavigationBar0` 窗口
touchable region 为空，桌面 Java 导航视图收不到事件，模块也无法注入桌面进程。

因此本模块只注入 `com.android.systemui`，挂钩
`android.view.InputEventReceiver.dispatchInputEvent(int, InputEvent)` 旁路观察同一路输入。
参考项目 [Gemini-For-HyperOS4](https://github.com/fulu2778/Gemini-For-HyperOS4) 同样确认了
「该 ROM 上模块进不去桌面」，并采用 SystemUI 侧输入监听实现自定义手势。

## 适用范围

- HyperOS 4 / Android 17（在 Redmi K90 Pro Max，OS4.0.6.0.XPMCNXM 上实测）
- LSPosed（支持 libxposed API 102）
- 已安装 Eta，并建议将 Eta 设为默认数字助理

## 安装

1. 安装 APK：`io.github.mangi.etagesture`
2. 在 LSPosed 中启用模块 **Eta 手势**
3. 作用域只需勾选：

   | 包名 | 用途 |
   |---|---|
   | `com.android.systemui` | 观察手势条触摸并唤起 Eta |

4. 重启手机
5. 打开「Eta 手势」确认状态为「LSPosed 已连接」，按需开关

## 实机验证

Redmi K90 Pro Max（HyperOS 4 / OS4.0.6.0.XPMCNXM，Android 17）：

| 操作 | 结果 |
| --- | --- |
| 单击手势条 | 不触发 |
| 双击手势条 | 只触发一次，Eta 助手浮窗覆盖当前应用，不切换任务 |
| 连续两次双击 | 各触发一次 |

对应日志：`[EtaGesture] 手势条输入监听已生效`、`[EtaGesture] 手势条双击，唤出 Eta 助手浮窗`。

## 排障

日志统一带 `[EtaGesture]` 前缀：

```text
adb logcat -s "[EtaGesture]"
```

正常启动应看到：

```text
模块载入 process=com.android.systemui
已挂钩 InputEventReceiver.dispatchInputEvent
手势条输入监听已生效          （第一次在底部横条上触摸后）
手势条双击，唤出 Eta 助手浮窗
```

常见问题：

- 双击无反应：确认 LSPosed 作用域勾选 `com.android.systemui` 且已重启；日志没有「已挂钩」说明模块未注入 SystemUI。
- 有触发日志但没有界面：把 Eta 设为默认数字助理，或给 Eta 悬浮窗权限；否则 Eta 的助手浮层无法显示。
- 单击就触发/触发两次：本模块已按事件时间去重并做 500ms 触发冷却；若仍复现，请附日志。
- 三键导航下不生效：双击判定只在手势导航模式启用。

## 构建

要求 JDK 17 + Android SDK 36；libxposed API 102 的 jar 已放在 `app/libs/libxposed/`。

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 已知限制

- 依赖 SystemUI 能收到手势条这一路输入；若某些 ROM 完全由其它进程独占，模块不会生效（日志可区分）。
- 不包含长按手势条、电源键等其它入口，避免与 Eta 自身或其它模块冲突。
- Google/厂商 ROM 升级后若方法签名变化，需要按新版本适配。
