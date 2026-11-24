package io.github.mangi.etagesture;

import android.content.Context;
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
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 手势条双击 → 唤出 Eta 助手浮窗（system_server 侧）。
 *
 * HyperOS 4 的手势条触摸由桌面原生手势运行时与 system_server 的手势监视器共同处理：
 * `dumpsys input` 里 `[Gesture Monitor] swipe-up` 属于 `com.miui.home`，
 * `[Gesture Monitor] miui-gesture` / `vibrator-input` 属于 system_server，均为覆盖全屏的
 * SPY 监视器。这里只挂钩框架的 `InputEventReceiver.dispatchInputEvent`，旁路观察
 * system_server 自己那几个常驻监视器的输入，不创建监听、不消费事件。
 *
 * 同一物理触摸会同时投递给多个 receiver，而桌面的手势识别可能只向其中一个流发送 CANCEL。
 * 因此每个 receiver 维护独立的手势状态，任一干净事件流识别出双击即可；触发后做短时冷却，
 * 避免多个流重复唤起。
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
                        Monitor.onInput((MotionEvent) argument, chain.getThisObject());
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

    /** 手势条区域内的双击观察者；每个 receiver 一个独立事件流状态。 */
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
        private static final WeakHashMap<Object, StreamState> streams = new WeakHashMap<>();
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

        static void onInput(MotionEvent event, Object receiver) {
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
                // 输入事件是全系统最热的路径：先用手势条几何做一次廉价过滤，非底部触摸直接返回。
                Context current = context;
                if (current == null) {
                    current = resolveContext();
                    if (current == null) {
                        if (!loggedMissingContext) {
                            loggedMissingContext = true;
                            log(module, "缺少 system_server Context，无法读取手势区域", null);
                        }
                        return;
                    }
                    context = current;
                }
                Geometry area = gestureBarArea(current);
                if (area == null || event.getRawY() < area.top) {
                    return;
                }
                if (preferences != null
                        && !preferences.getBoolean(Constants.KEY_ENABLED, true)) {
                    return;
                }
                if (!isGestureNavigation(current) || isKeyguardLocked(current)) {
                    return;
                }
                if (!loggedFirstTouch) {
                    loggedFirstTouch = true;
                    log(module, "手势条输入监听已生效", null);
                }
                StreamState state = streams.get(receiver);
                if (state == null) {
                    state = new StreamState(createDetector(current));
                    streams.put(receiver, state);
                }
                if (state.isDuplicate(event)) {
                    return;
                }
                boolean triggered = state.detector.onEvent(
                        action,
                        event.getEventTime(),
                        event.getRawX(),
                        event.getRawY(),
                        event.getPointerCount(),
                        true);
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
                boolean vibrate = preferences == null
                        || preferences.getBoolean(Constants.KEY_VIBRATE, true);
                MAIN.post(() -> EtaWake.launch(launchContext, module, vibrate));
            }
        }

        private static Context resolveContext() {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method currentThread = activityThread.getDeclaredMethod("currentActivityThread");
                currentThread.setAccessible(true);
                Object thread = currentThread.invoke(null);
                if (thread == null) {
                    return null;
                }
                Method systemContext = activityThread.getDeclaredMethod("getSystemContext");
                systemContext.setAccessible(true);
                Object value = systemContext.invoke(thread);
                return value instanceof Context ? (Context) value : null;
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
                // HyperOS 4 的桌面原生手势运行时以 Settings.Secure.navigation_mode 为准：
                // 0 = 三键导航，1 = 双键导航，2 = 手势导航。
                int mode = android.provider.Settings.Secure.getInt(
                        current.getContentResolver(), "navigation_mode", -1);
                if (mode < 0) {
                    Resources resources = current.getResources();
                    int id = resources.getIdentifier(
                            "config_navBarInteractionMode", "integer", "android");
                    mode = id == 0 ? -1 : resources.getInteger(id);
                }
                result = mode != 0 && mode != 1;
            } catch (Throwable ignored) {
                // 读不到时按手势导航处理，避免新 ROM 直接失效。
            }
            gestureNavigation = result;
            return result;
        }

        private static boolean isKeyguardLocked(Context current) {
            try {
                Object keyguard = current.getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguard == null) {
                    return false;
                }
                Method locked = keyguard.getClass().getMethod("isKeyguardLocked");
                return Boolean.TRUE.equals(locked.invoke(keyguard));
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

        private static final class Geometry {
            final int height;
            final float top;

            Geometry(int height, float top) {
                this.height = height;
                this.top = top;
            }
        }

        /** 单个接收通道的去重水位线与双击识别器。 */
        private static final class StreamState {
            private static final int RECENT_EVENTS = 12;

            final DoubleTapDetector detector;
            private long lastEventTime = Long.MIN_VALUE;
            private final long[] recentTimes = new long[RECENT_EVENTS];
            private final int[] recentActions = new int[RECENT_EVENTS];
            private final int[] recentXBits = new int[RECENT_EVENTS];
            private final int[] recentYBits = new int[RECENT_EVENTS];
            private int recentIndex;
            private int recentCount;

            StreamState(DoubleTapDetector detector) {
                this.detector = detector;
                java.util.Arrays.fill(recentTimes, Long.MIN_VALUE);
            }

            boolean isDuplicate(MotionEvent event) {
                long time = event.getEventTime();
                int action = event.getActionMasked();
                int xBits = Float.floatToRawIntBits(event.getRawX());
                int yBits = Float.floatToRawIntBits(event.getRawY());
                if (time < lastEventTime) {
                    return true;
                }
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
        }
    }
}
