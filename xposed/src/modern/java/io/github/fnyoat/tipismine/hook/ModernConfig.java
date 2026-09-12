package io.github.fnyoat.tipismine.hook;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedInterface;

/**
 * modern 风味的配置读取（hook 侧，SystemUI 进程）。
 *
 * <p>经官方 {@link XposedInterface#getRemotePreferences} 读取框架存储的配置，
 * 无 SELinux 跨进程读问题。远端 prefs 对被 hook 的应用是只读的，
 * 写入由模块 UI（另一进程）经 libxposed service 完成。
 */
public final class ModernConfig implements ConfigStore {

    private final SharedPreferences prefs;

    public ModernConfig(XposedInterface framework) {
        this.prefs = framework.getRemotePreferences(Config.PREF_NAME);
    }

    @Override
    public Config load() {
        Config cfg = new Config(
                SlotMode.from(prefs.getString(Config.KEY_OWNER_MODE, SlotMode.MODE_DEFAULT)),
                prefs.getString(Config.KEY_OWNER_TEXT, ""),
                SlotMode.from(prefs.getString(Config.KEY_VPN_MODE, SlotMode.MODE_DEFAULT)),
                prefs.getString(Config.KEY_VPN_TEXT, ""),
                prefs.getBoolean(Config.KEY_REWRITE_WHOLE, false),
                prefs.getString(Config.KEY_WHOLE_TEXT, ""),
                prefs.getBoolean(Config.KEY_EXPOSE_API, false),
                prefs.getInt(Config.KEY_API_PORT, 8080),
                prefs.getString(Config.KEY_API_KEY, ""),
                prefs.getFloat(Config.KEY_REFRESH_INTERVAL, 1f),
                prefs.getBoolean(Config.KEY_ENABLED, true),
                prefs.getBoolean(Config.KEY_EXPRESSIONS, true),
                prefs.getBoolean(Config.KEY_CONTENT_SCAN, true),
                prefs.getString(Config.KEY_CUSTOM_KEYS, ""),
                prefs.getBoolean(Config.KEY_DEBUG_PAGE_REMOVED, false),
                prefs.getString(Config.KEY_SCAN_TOKEN, ""),
                prefs.getString(Config.KEY_SCAN_RESULTS, ""),
                prefs.getString(Config.KEY_API_BIND_IP, "0.0.0.0"),
                prefs.getString(Config.KEY_API_SCHEME, "http"));
        // 分离开关关闭（默认）时不读 lock_* 键，lockscreen 保持 null → hook 零额外开销。
        if (prefs.getBoolean(Config.KEY_SEPARATE_LOCKSCREEN, false)) {
            cfg.lockscreen = new LockScreenPrefs(
                    prefs.getBoolean(Config.KEY_LOCK_OWNER_FOLLOW, true),
                    prefs.getString(Config.KEY_LOCK_OWNER_MODE, SlotMode.MODE_DEFAULT),
                    prefs.getString(Config.KEY_LOCK_OWNER_TEXT, ""),
                    prefs.getBoolean(Config.KEY_LOCK_VPN_FOLLOW, true),
                    prefs.getString(Config.KEY_LOCK_VPN_MODE, SlotMode.MODE_DEFAULT),
                    prefs.getString(Config.KEY_LOCK_VPN_TEXT, ""),
                    prefs.getBoolean(Config.KEY_LOCK_REWRITE_WHOLE, false),
                    prefs.getString(Config.KEY_LOCK_WHOLE_TEXT, ""));
        }
        return cfg;
    }
}