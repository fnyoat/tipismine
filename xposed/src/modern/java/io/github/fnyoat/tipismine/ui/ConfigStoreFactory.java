package io.github.fnyoat.tipismine.ui;

import android.app.Activity;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import io.github.fnyoat.tipismine.hook.ConfigAccess;
import io.github.fnyoat.tipismine.hook.ModernUiConfig;

/**
 * modern 风味的 UI 侧配置工厂：经 libxposed 服务绑定获取可写远程 prefs。
 */
final class ConfigStoreFactory {

    private static final ModernUiConfig INSTANCE = new ModernUiConfig();
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    private ConfigStoreFactory() {
    }

    static ConfigAccess create(Activity a) {
        return INSTANCE;
    }

    /** 在 Activity onCreate/onStart 时绑定服务；绑定成功后即“激活”（绿），并实时刷新横幅。 */
    static void onActivityStart(Activity a) {
        if (!LISTENER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                INSTANCE.attach(service);
                if (a instanceof ConfigActivity) {
                    ((ConfigActivity) a).refreshBanner();
                    ((ConfigActivity) a).refreshOnServiceBound();
                }
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (a instanceof ConfigActivity) {
                    ((ConfigActivity) a).refreshBanner();
                }
            }
        });
    }
}