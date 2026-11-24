package io.github.mangi.etagesture;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/** libxposed API 102 模块入口：只作用于 system_server。 */
public final class HookEntry extends XposedModule {
    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, Constants.TAG, "模块载入 process=" + param.getProcessName());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            GestureBarHook.install(this, param.getClassLoader());
        } catch (Exception exception) {
            log(Log.ERROR, Constants.TAG, "安装手势条 Hook 失败: " + exception);
        }
    }
}
