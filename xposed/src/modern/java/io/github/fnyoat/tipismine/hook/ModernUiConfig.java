package io.github.fnyoat.tipismine.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import io.github.libxposed.service.XposedService;

/**
 * modern 风味的配置读写（UI / 模块 App 进程）。
 *
 * <p>写入经框架注入的 {@link XposedService} 的远程 prefs——实时、跨进程、无 SELinux 问题。
 * 若服务尚未绑定到，则暂存待写值，绑定成功后一次性写入。
 */
public final class ModernUiConfig implements ConfigAccess {

    private volatile XposedService service;

    /** UI 打开后的暂存写入（服务未就绪时）。 */
    private volatile Map<String, Object> pending;

    public void attach(XposedService svc) {
        this.service = svc;
        Map<String, Object> p = pending;
        if (p != null) {
            pending = null;
            writeValues(svc, p);
        }
    }

    @Override
    public Config load() {
        XposedService svc = service;
        if (svc == null) {
            return new Config(SlotMode.DEFAULT, "", SlotMode.DEFAULT, "", false, "", false, 8080, "", 1, true);
        }
        SharedPreferences prefs = svc.getRemotePreferences(Config.PREF_NAME);
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

    @Override
    public void write(Context ctx, Map<String, Object> values) {
        XposedService svc = service;
        if (svc == null) {
            pending = values;
            return;
        }
        writeValues(svc, values);
    }

    @Override
    public boolean isActive() {
        return service != null;
    }

    private static void writeValues(XposedService svc, Map<String, Object> values) {
        SharedPreferences.Editor ed = svc.getRemotePreferences(Config.PREF_NAME).edit();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof String) ed.putString(e.getKey(), (String) v);
            else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
        }
        ed.apply();
    }
}