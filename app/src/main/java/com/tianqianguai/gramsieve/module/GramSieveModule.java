package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class GramSieveModule extends XposedModule {
    private static final String TELEGRAM_PACKAGE = "org.telegram.messenger";
    // Conso adaptation: Conso is a Telegram fork whose application package is
    // global.xfinite.conso, while its Java classes remain org.telegram.messenger.*.
    // Accept both so the module loads for Conso as well as official Telegram.
    private static final String CONSO_PACKAGE = "global.xfinite.conso";
    private static final String TAG = "GramSieve";

    private final TelegramHookInstaller hookInstaller = new TelegramHookInstaller(this);

    private static boolean isSupportedPackage(String packageName) {
        return TELEGRAM_PACKAGE.equals(packageName) || CONSO_PACKAGE.equals(packageName);
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        ModuleLogger.setHookProcessMode(this);
        ModuleLogger.lifecycle(TAG, "Module loaded by " + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!isSupportedPackage(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        ModuleLogger.lifecycle(TAG, "Telegram package loaded; waiting for app class loader");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!isSupportedPackage(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        try {
            hookInstaller.install(param.getClassLoader(), param.getApplicationInfo());
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_LIFECYCLE, TAG, "Failed to install Telegram hooks", throwable);
        }
    }
}
