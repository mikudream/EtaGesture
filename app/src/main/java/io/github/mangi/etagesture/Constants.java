package io.github.mangi.etagesture;

/** 跨进程共享的固定标识。 */
public final class Constants {
    private Constants() {
    }

    public static final String TAG = "[EtaGesture]";

    /** libxposed RemotePreferences 组名；UI 写入与 Hook 读取必须一致。 */
    public static final String PREFS_GROUP = "eta_gesture";

    /** 双击手势条开关。 */
    public static final String KEY_ENABLED = "enabled";

    /** 唤醒成功后的震动反馈开关。 */
    public static final String KEY_VIBRATE = "vibrate";

    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    public static final String ETA_PACKAGE = "io.github.mangi.eta";

    /** Eta 的系统助理桥接 Activity：与长按电源键唤起同一个助手浮窗。 */
    public static final String ETA_ASSIST_ACTIVITY =
            "io.github.mangi.eta.agent.voice.EtaVoiceAssistActivity";
}
