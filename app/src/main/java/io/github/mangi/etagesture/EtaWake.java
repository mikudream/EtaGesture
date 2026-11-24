package io.github.mangi.etagesture;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 从 system_server 唤出 Eta 助手。
 *
 * 首选与长按电源键完全相同的路径：通过 `voiceinteraction` Binder 调
 * `showSessionForActiveService`，由系统直接显示 Eta 的助理会话；
 * 失败时回退为启动 Eta 的 ASSIST 桥接 Activity。system_server 的启动不受
 * 第三方应用后台启动限制影响。
 */
final class EtaWake {
    private EtaWake() {
    }

    static void launch(Context context, XposedModule module) {
        if (showAssistantSession(module)) {
            return;
        }
        startAssistActivity(context, module);
    }

    private static boolean showAssistantSession(XposedModule module) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "voiceinteraction");
            if (binder == null) {
                log(module, "voiceinteraction 服务不可用", null);
                return false;
            }
            Object service = Class.forName(
                            "com.android.internal.app.IVoiceInteractionManagerService$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            Method show = null;
            for (Method method : service.getClass().getMethods()) {
                if ("showSessionForActiveService".equals(method.getName())
                        && method.getParameterTypes().length == 5) {
                    show = method;
                    break;
                }
            }
            if (show == null) {
                log(module, "voiceinteraction 缺少 showSessionForActiveService/5", null);
                return false;
            }
            show.setAccessible(true);
            Object result = show.invoke(service, new Bundle(), showFlags(), null, null, null);
            if (Boolean.TRUE.equals(result)) {
                log(module, "已通过助理会话唤出 Eta", null);
                return true;
            }
            log(module, "助理会话拒绝显示，回退 ASSIST Activity", null);
            return false;
        } catch (Throwable t) {
            log(module, "助理会话调用失败", t);
            return false;
        }
    }

    /** 与 Eta 电源键入口一致的展示标志：带屏截图 + 结构化屏幕内容。 */
    private static int showFlags() {
        int flags = 1 << 5; // SHOW_SOURCE_PUSH_TO_TALK
        flags |= constant("SHOW_WITH_ASSIST", 1);
        flags |= constant("SHOW_WITH_SCREENSHOT", 2);
        flags |= constant("SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT", 0);
        return flags;
    }

    private static int constant(String name, int fallback) {
        try {
            Field field = VoiceInteractionSession.class.getField(name);
            return field.getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void startAssistActivity(Context context, XposedModule module) {
        try {
            Intent intent = new Intent(Intent.ACTION_ASSIST)
                    .setClassName(Constants.ETA_PACKAGE, Constants.ETA_ASSIST_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(intent);
            log(module, "已启动 Eta ASSIST 桥接 Activity", null);
        } catch (Throwable t) {
            log(module, "启动 Eta 助手失败（确认 Eta 已安装）", t);
        }
    }

    private static void log(XposedModule module, String message, Throwable throwable) {
        if (module == null) {
            return;
        }
        try {
            if (throwable == null) {
                module.log(Log.INFO, Constants.TAG, message);
            } else {
                module.log(Log.WARN, Constants.TAG, message + ": " + throwable);
            }
        } catch (Throwable ignored) {
            // 忽略日志失败。
        }
    }
}
