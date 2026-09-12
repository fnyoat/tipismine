package io.github.fnyoat.tipismine.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

/**
 * legacy 风味的配置访问（老 Xposed / 老 LSPosed / Android 7 兼容）。
 *
 * <ul>
 *   <li>hook 侧（SystemUI 进程）：用 {@link XSharedPreferences} 跨进程读模块 prefs。</li>
 *   <li>UI 侧：写 {@link SharedPreferences} 后显式 chmod，使 Android 7+ 的
 *       SystemUI 也能经 {@link XSharedPreferences} 读到。</li>
 * </ul>
 *
 * <p><strong>安全</strong>：公开 prefs 为保证 SystemUI 可读被迫 chmod 世界可读，
 * 因此<b>绝不</b>把 {@code api_key} 写进其中——它只被模块 UI 进程（HTTP 服务）
 * 使用，SystemUI hook 侧也用不到。api_key 单独存一份 {@code MODE_PRIVATE} 私有
 * prefs（不 chmod），仅本 App 可读。
 */
public final class LegacyConfig implements ConfigStore, ConfigAccess {

    private static final String SECURE_PREF_NAME = "tipismine_secure";

    private final XSharedPreferences prefs;
    private volatile Context appContext;

    public LegacyConfig() {
        prefs = new XSharedPreferences(TipIsMine.PACKAGE_NAME, Config.PREF_NAME);
        prefs.reload();
    }

    /** UI 侧注入 appContext，用于读写私有的 api_key 存储。 */
    public void attachContext(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public Config load() {
        prefs.reload();
        String apiKey = secureApiKey();
        Config cfg = new Config(
                SlotMode.from(prefs.getString(Config.KEY_OWNER_MODE, SlotMode.MODE_DEFAULT)),
                prefs.getString(Config.KEY_OWNER_TEXT, ""),
                SlotMode.from(prefs.getString(Config.KEY_VPN_MODE, SlotMode.MODE_DEFAULT)),
                prefs.getString(Config.KEY_VPN_TEXT, ""),
                prefs.getBoolean(Config.KEY_REWRITE_WHOLE, false),
                prefs.getString(Config.KEY_WHOLE_TEXT, ""),
                prefs.getBoolean(Config.KEY_EXPOSE_API, false),
                prefs.getInt(Config.KEY_API_PORT, 8080),
                apiKey,
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

    @Override
    public void write(Context ctx, Map<String, Object> values) {
        // api_key 单独存私有 prefs，绝不进入世界可读的公开 prefs。
        Context app = ctx.getApplicationContext();
        this.appContext = app;
        Object key = values.get(Config.KEY_API_KEY);
        if (key instanceof String) {
            app.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(Config.KEY_API_KEY, (String) key).apply();
        }

        SharedPreferences sp = app.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (Config.KEY_API_KEY.equals(e.getKey())) {
                continue; // 敏感字段不入公开存储
            }
            Object v = e.getValue();
            if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof String) ed.putString(e.getKey(), (String) v);
            else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
        }
        ed.commit(); // 同步落盘，确保下面的 chmod 能真正作用到已写出的文件

        // Android 7+ 需显式放开读权限，SystemUI 才能经 XSharedPreferences 读取。
        File f = new File(app.getFilesDir().getParentFile(), "shared_prefs/" + Config.PREF_NAME + ".xml");
        if (f.exists()) {
            setWorldReadable(f);
        }
        File dir = f.getParentFile();
        if (dir != null && dir.exists()) {
            setWorldReadable(dir);
        }
    }

    @Override
    public boolean isActive() {
        // legacy 框架（老 Xposed / 老 LSPosed）会向模块自身 App 进程注入框架类，
        // 因此本进程里能加载到 XposedBridge 即视为已在框架中激活。
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读私有 api_key（仅 UI/模块 App 进程可读；SystemUI hook 侧读不到 → 返回空，无影响）。 */
    private String secureApiKey() {
        Context app = appContext;
        if (app == null) return "";
        try {
            return app.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
                    .getString(Config.KEY_API_KEY, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void setWorldReadable(File fi) {
        try {
            fi.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }
}