package io.github.mangi.etagesture;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 手势条双击 → 唤出 Eta 助手浮窗。
 *
 * HyperOS 4 的手势条触摸由桌面原生手势运行时分发（`dumpsys input` 里
 * `[Gesture Monitor] swipe-up` 属于 `com.miui.home`，桌面 Java 导航视图拿不到事件，
 * 模块也无法注入桌面进程）。这里改为在 SystemUI 中旁路观察同一路输入：
 * 只挂钩 `InputEventReceiver.dispatchInputEvent`，不创建额外输入监听、不改事件分发、
 * 不消费任何触摸，只在底部手势条区域识别双击后启动 Eta 的 ASSIST 桥接 Activity。
 */
public final class GestureBarHook {
    private GestureBarHook() {
    }

    public static void install(XposedModule module, ClassLoader classLoader) throws Exception {
        Class<?> receiverClass = Class.forName("android.view.InputEventReceiver", false, classLoader);
        Method dispatch = receiverClass.getDeclaredMethod(
                "dispatchInputEvent", int.class, InputEvent.class);
        dispatch.setAccessible(true);

        SharedPreferences preferences = null;
        try {
            preferences = module.getRemotePreferences(Constants.PREFS_GROUP);
        } catch (Throwable t) {
            log(module, "RemotePreferences 不可用，使用默认开关", t);
        }
        Monitor.install(module, preferences);

        module.hook(dispatch)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("eta.gesture-bar-input")
                .intercept(chain -> {
                    Object argument = chain.getArg(1);
                    if (argument instanceof MotionEvent) {
                        Monitor.onInput((MotionEvent) argument);
                    }
                    return chain.proceed();
                });
        log(module, "已挂钩 InputEventReceiver.dispatchInputEvent", null);
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
            // 日志失败不能影响输入链路。
        }
    }

    /** 悬浮条区域内的双击观察者；状态只在输入回调内串行更新。 */
    private static final class Monitor {
        private static final Object LOCK = new Object();
        private static final Handler MAIN = new Handler(Looper.getMainLooper());

        private static final long TRIGGER_COOLDOWN_MS = 500L;
        private static final float MIN_GESTURE_BAR_DP = 24f;

        private static XposedModule module;
        private static SharedPreferences preferences;
        private static Context context;
        private static Geometry geometry;
        private static Boolean gestureNavigation;
        private static DoubleTapDetector detector;
        private static long lastEventTime = Long.MIN_VALUE;
        private static final int RECENT_EVENTS = 12;
        private static final long[] recentTimes = new long[RECENT_EVENTS];
        private static final int[] recentActions = new int[RECENT_EVENTS];
        private static final int[] recentXBits = new int[RECENT_EVENTS];
        private static final int[] recentYBits = new int[RECENT_EVENTS];
        private static int recentIndex;
        private static int recentCount;

        static {
            java.util.Arrays.fill(recentTimes, Long.MIN_VALUE);
        }
        private static long lastTriggerUptime;
        private static boolean loggedFirstTouch;
        private static boolean loggedMissingContext;

        private Monitor() {
        }

        static void install(XposedModule xposedModule, SharedPreferences remotePreferences) {
            synchronized (LOCK) {
                module = xposedModule;
                preferences = remotePreferences;
            }
        }

        static void onInput(MotionEvent event) {
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                return;
            }
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                    && action != MotionEvent.ACTION_POINTER_DOWN
                    && action != MotionEvent.ACTION_POINTER_UP) {
                return;
            }
            synchronized (LOCK) {
                // SystemUI 可能同时持有多个 observe-only 输入通道，同一事件会重复到达且可能乱序：
                // 先用事件时间水位线丢弃滞后副本，再比对同毫秒内的完整签名。
                if (isStaleOrDuplicate(event)) {
                    return;
                }
                if (preferences != null
                        && !preferences.getBoolean(Constants.KEY_ENABLED, true)) {
                    return;
                }
                Context current = context;
                if (current == null) {
                    current = resolveContext();
                    if (current == null) {
                        if (!loggedMissingContext) {
                            loggedMissingContext = true;
                            log(module, "缺少 SystemUI Context，无法读取手势区域", null);
                        }
                        return;
                    }
                    context = current;
                }
                if (!isGestureNavigation(current) || isKeyguardLocked(current)) {
                    if (detector != null) {
                        detector.reset();
                    }
                    return;
                }
                Geometry area = gestureBarArea(current);
                if (area == null) {
                    return;
                }
                if (!loggedFirstTouch) {
                    loggedFirstTouch = true;
                    log(module, "手势条输入监听已生效", null);
                }
                if (detector == null) {
                    detector = createDetector(current);
                }
                boolean triggered = detector.onEvent(
                        action,
                        event.getEventTime(),
                        event.getRawX(),
                        event.getRawY(),
                        event.getPointerCount(),
                        event.getRawY() >= area.top);
                if (!triggered) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastTriggerUptime < TRIGGER_COOLDOWN_MS) {
                    return;
                }
                lastTriggerUptime = now;
                log(module, "手势条双击，唤出 Eta 助手浮窗", null);
                Context launchContext = current;
                MAIN.post(() -> launchEta(launchContext));
            }
        }

        private static boolean isStaleOrDuplicate(MotionEvent event) {
            long time = event.getEventTime();
            int action = event.getActionMasked();
            int xBits = Float.floatToRawIntBits(event.getRawX());
            int yBits = Float.floatToRawIntBits(event.getRawY());
            // 滞后于当前水位线的副本直接丢弃。
            if (time < lastEventTime) {
                return true;
            }
            // 同一事件会被多个 observe-only 通道各投递一次，可能乱序到达；
            // 用一个小环记录最近事件的完整签名，命中即视为副本。
            for (int i = 0; i < recentCount; i++) {
                if (recentTimes[i] == time && recentActions[i] == action
                        && recentXBits[i] == xBits && recentYBits[i] == yBits) {
                    return true;
                }
            }
            recentTimes[recentIndex] = time;
            recentActions[recentIndex] = action;
            recentXBits[recentIndex] = xBits;
            recentYBits[recentIndex] = yBits;
            recentIndex = (recentIndex + 1) % RECENT_EVENTS;
            if (recentCount < RECENT_EVENTS) {
                recentCount++;
            }
            lastEventTime = time;
            return false;
        }

        private static Context resolveContext() {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method currentApplication =
                        activityThread.getDeclaredMethod("currentApplication");
                currentApplication.setAccessible(true);
                Object application = currentApplication.invoke(null);
                return application instanceof Context ? (Context) application : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private static boolean isGestureNavigation(Context current) {
            Boolean cached = gestureNavigation;
            if (cached != null) {
                return cached;
            }
            boolean result = true;
            try {
                Resources resources = current.getResources();
                int id = resources.getIdentifier(
                        "config_navBarInteractionMode", "integer", "android");
                // 0 = 三键导航，1 = 双键导航；读不到时按手势导航处理，避免新 ROM 直接失效。
                int mode = id == 0 ? -1 : resources.getInteger(id);
                result = mode != 0 && mode != 1;
            } catch (Throwable ignored) {
                // 保持默认按手势导航处理。
            }
            gestureNavigation = result;
            return result;
        }

        private static boolean isKeyguardLocked(Context current) {
            try {
                KeyguardManager keyguardManager =
                        (KeyguardManager) current.getSystemService(Context.KEYGUARD_SERVICE);
                return keyguardManager != null && keyguardManager.isKeyguardLocked();
            } catch (Throwable t) {
                return false;
            }
        }

        private static Geometry gestureBarArea(Context current) {
            WindowManager windowManager =
                    (WindowManager) current.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return null;
            }
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            int height = metrics.getBounds().height();
            Geometry cached = geometry;
            if (cached != null && cached.height == height) {
                return cached;
            }
            WindowInsets insets = metrics.getWindowInsets();
            float density = current.getResources().getDisplayMetrics().density;
            int bottom = Math.max(
                    Math.max(
                            insets.getInsetsIgnoringVisibility(
                                    WindowInsets.Type.mandatorySystemGestures()).bottom,
                            insets.getInsetsIgnoringVisibility(
                                    WindowInsets.Type.navigationBars()).bottom),
                    (int) (MIN_GESTURE_BAR_DP * density));
            if (bottom <= 0 || bottom > height / 4) {
                return null;
            }
            Geometry result = new Geometry(height, height - bottom);
            geometry = result;
            return result;
        }

        private static DoubleTapDetector createDetector(Context current) {
            ViewConfiguration configuration = ViewConfiguration.get(current);
            return new DoubleTapDetector(
                    configuration.getScaledTouchSlop(),
                    configuration.getScaledDoubleTapSlop(),
                    ViewConfiguration.getDoubleTapTimeout(),
                    ViewConfiguration.getLongPressTimeout());
        }

        private static void launchEta(Context current) {
            try {
                Intent intent = new Intent(Intent.ACTION_ASSIST)
                        .setClassName(Constants.ETA_PACKAGE, Constants.ETA_ASSIST_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                current.startActivity(intent);
            } catch (Throwable t) {
                log(module, "启动 Eta 助手失败（确认 Eta 已安装）", t);
            }
        }

        private static final class Geometry {
            final int height;
            final float top;

            Geometry(int height, float top) {
                this.height = height;
                this.top = top;
            }
        }
    }
}
