package io.github.mangi.etagesture;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/** libxposed API 102 模块入口。 */
public final class HookEntry extends XposedModule {
    private String processName = "";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, Constants.TAG, "模块载入 process=" + processName);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Constants.SYSTEM_UI_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        // 只在 SystemUI 主进程安装，避免注入子进程重复 Hook。
        if (!Constants.SYSTEM_UI_PACKAGE.equals(processName)) {
            return;
        }
        try {
            GestureBarHook.install(this, param.getClassLoader());
        } catch (Exception exception) {
            log(Log.ERROR, Constants.TAG, "安装手势条 Hook 失败: " + exception);
        }
    }
}
