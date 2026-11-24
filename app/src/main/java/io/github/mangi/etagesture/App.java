package io.github.mangi.etagesture;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 UI 进程的 Application。
 *
 * 注册 [XposedServiceHelper] 监听器后，框架通过 APK 内的 XposedProvider 推送 binder，
 * UI 即可用 [XposedService] 写入 RemotePreferences，实时同步到 SystemUI 里的 Hook。
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    public interface ServiceListener {
        void onServiceChanged(XposedService service);
    }

    private static final CopyOnWriteArraySet<ServiceListener> LISTENERS =
            new CopyOnWriteArraySet<>();

    private static volatile XposedService service;

    public static XposedService service() {
        return service;
    }

    public static void addServiceListener(ServiceListener listener) {
        LISTENERS.add(listener);
        notifyListener(listener, service);
    }

    public static void removeServiceListener(ServiceListener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable ignored) {
            // 框架未安装时应用仍可正常打开设置页。
        }
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        notifyListeners(boundService);
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
            notifyListeners(null);
        }
    }

    private static void notifyListeners(XposedService current) {
        for (ServiceListener listener : LISTENERS) {
            notifyListener(listener, current);
        }
    }

    private static void notifyListener(ServiceListener listener, XposedService current) {
        try {
            listener.onServiceChanged(current);
        } catch (Throwable ignored) {
            // 单个监听器异常不影响其他监听器。
        }
    }
}
