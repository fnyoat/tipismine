package io.github.fnyoat.tipismine.ui;

import android.app.Activity;

import io.github.fnyoat.tipismine.hook.ConfigAccess;
import io.github.fnyoat.tipismine.hook.LegacyConfig;

/**
 * legacy 风味的 UI 侧配置工厂：XSharedPreferences + chmod（api_key 单独私有存储）。
 */
final class ConfigStoreFactory {

    private ConfigStoreFactory() {
    }

    static ConfigAccess create(Activity a) {
        LegacyConfig cfg = new LegacyConfig();
        cfg.attachContext(a);
        return cfg;
    }

    static void onActivityStart(Activity a) {
        // legacy 无需绑定服务。
    }
}