package io.github.fnyoat.tipismine.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.fnyoat.tipismine.hook.ComposePrompt;
import io.github.fnyoat.tipismine.hook.Config;
import io.github.fnyoat.tipismine.hook.ConfigAccess;
import io.github.fnyoat.tipismine.R;
import io.github.fnyoat.tipismine.hook.ExpressionContext;
import io.github.fnyoat.tipismine.hook.ExpressionEngine;
import io.github.fnyoat.tipismine.hook.SlotMode;

/**
 * 配置页（双 flavor 共用 UI）。深色模式自动跟随系统。
 *
 * <p>顶部横幅：绿=模块已激活；红=未激活（仅反映是否激活，不反映 hook 成败）。
 * owner / vpn 各为"默认 / 显示 / 隐藏"三态 + 自定义文本；另有整段替换（方案二选一）。
 * 右上角"设置"进入 {@link SettingsActivity}（更新速度 / 网络 API / 表达式帮助）。
 */
public class ConfigActivity extends Activity {

    /* java.util.function 仅 API 24+，为兼容 API 23 (Android 6) 自拟功能接口 */
    interface OnIdx { void on(int index); }
    interface OnText { void on(String text); }

    private final Map<String, Object> pending = new LinkedHashMap<>();

    private ConfigAccess store;
    private TextView bannerTv;
    private TextView previewTv;
    private ConfigHttpServer httpServer;
    /** 构造 UI 时配置服务是否已就绪。未就绪时编辑区读到的是默认值（全空），
     *  等服务绑定后需重建整个页面，以真实配置回填。 */
    private boolean uiBuiltWithService;
    /** 会话内唯一次数的"绑定后重建"守卫：绑定回调可能多次，只重建一次。 */
    private boolean pendingBoundRebuild;

    @Override
    protected void onStart() {
        super.onStart();
        ConfigStoreFactory.onActivityStart(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = ConfigStoreFactory.create(this);
        uiBuiltWithService = store.isActive();
        if (!uiBuiltWithService) {
            // 服务未就绪时此刻读到的必然是默认值；记录以便绑定后重建回填真实配置。
            pendingBoundRebuild = true;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);

        title(root, getString(R.string.app_name));
        subtitle(root, getString(R.string.config_main_subtitle));

        bannerTv = new TextView(this);
        bannerTv.setTextSize(14);
        bannerTv.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        bannerTv.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bannerLp.topMargin = dp(8);
        root.addView(bannerTv, bannerLp);
        updateBanner();

        /* ---- 功能总开关：关闭则放行系统原值（调试 / 临时停用） ---- */
        final android.widget.Switch enableSwitch = new android.widget.Switch(this);
        enableSwitch.setText(getString(R.string.enabled_switch));
        enableSwitch.setTextSize(16);
        enableSwitch.setTextColor(textColor());
        enableSwitch.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        switchLp.topMargin = dp(8);
        root.addView(enableSwitch, switchLp);
        enableSwitch.setChecked(cfg().enabled);
        applySwitchLook(enableSwitch); // 开启时轨道绿色，醒目
        // setChecked 在注册 listener 之前完成，不会触发回调，无需守卫。
        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            try {
                pending.put(Config.KEY_ENABLED, isChecked);
            } catch (Throwable t) {
                enableSwitch.setChecked(cfg().enabled);
            }
            applySwitchLook(enableSwitch);
        });

        /* ---- 实时预览 ---- */
        section(root, getString(R.string.preview_section));
        previewTv = new TextView(this);
        previewTv.setTextSize(15);
        previewTv.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(previewTv);

        refreshPreview();

        /* ---- 编辑方案：修改字段 / 全部修改，必须且只能选一个 ---- */
        section(root, getString(R.string.scheme_section));
        final boolean[] wholeScheme = {cfg().wholeEnabled()};
        LinearLayout schemeBar = new LinearLayout(this);
        schemeBar.setOrientation(LinearLayout.HORIZONTAL);
        final TextView fieldTab = schemeTab(schemeBar, getString(R.string.scheme_field_tab));
        final TextView wholeTab = schemeTab(schemeBar, getString(R.string.scheme_whole_tab));

        LinearLayout fieldBox = new LinearLayout(this);
        fieldBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout wholeBox = new LinearLayout(this);
        wholeBox.setOrientation(LinearLayout.VERTICAL);

        /* ---- 方案A：修改字段（owner / vpn 各自设置） ---- */
        section(fieldBox, getString(R.string.owner_section));
        modeInputRow(fieldBox, getString(R.string.owner_title), null,
                cfg().ownerMode, cfg().ownerText,
                idx -> pending.put(Config.KEY_OWNER_MODE, idxName(idx)),
                s -> pending.put(Config.KEY_OWNER_TEXT, s));
        subtitle(fieldBox, getString(R.string.owner_example));

        section(fieldBox, getString(R.string.vpn_title));
        modeInputRow(fieldBox, getString(R.string.vpn_title), null,
                cfg().vpnMode, cfg().vpnText,
                idx -> pending.put(Config.KEY_VPN_MODE, idxName(idx)),
                s -> pending.put(Config.KEY_VPN_TEXT, s));
        subtitle(fieldBox, getString(R.string.vpn_example));

        /* ---- 方案B：全部修改（整句替换） ---- */
        section(wholeBox, getString(R.string.whole_section));
        final EditText wholeEt = new EditText(this);
        wholeEt.setText(cfg().wholeEnabled() ? cfg().wholeText : "");
        wholeEt.setSingleLine(false);
        wholeEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        wholeEt.setGravity(Gravity.TOP | Gravity.START);
        wholeEt.setMinLines(2);
        wholeEt.setHint(getString(R.string.whole_hint));
        wholeEt.setTextColor(textColor());
        wholeEt.setHintTextColor(subColor());
        GradientDrawable wholeBg = new GradientDrawable();
        wholeBg.setCornerRadius(dp(8));
        wholeBg.setStroke(dp(1), accent());
        wholeEt.setBackground(wholeBg);
        wholeEt.setPadding(dp(10), dp(6), dp(10), dp(6));
        wholeEt.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wholeEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                pending.put(Config.KEY_WHOLE_TEXT, s == null ? "" : s.toString().trim());
            }
        });
        wholeBox.addView(wholeEt);
        subtitle(wholeBox, getString(R.string.whole_example));

        root.addView(schemeBar);
        root.addView(fieldBox);
        root.addView(wholeBox);

        /* 方案切换：点击高亮另一 tab，折叠当前设置项；
           初始不用写 pending（仅当用户真正点击切换时才记录 rewrite_whole）。 */
        Runnable applyScheme = () -> {
            boolean whole = wholeScheme[0];
            pending.put(Config.KEY_REWRITE_WHOLE, whole);
            fieldTab.setTextColor(whole ? subColor() : textColor());
            wholeTab.setTextColor(whole ? textColor() : subColor());
            setTabEnabled(fieldTab, !whole);
            setTabEnabled(wholeTab, whole);
            fieldBox.setVisibility(whole ? android.view.View.GONE : android.view.View.VISIBLE);
            wholeBox.setVisibility(whole ? android.view.View.VISIBLE : android.view.View.GONE);
        };
        fieldTab.setOnClickListener(v -> { wholeScheme[0] = false; applyScheme.run(); });
        wholeTab.setOnClickListener(v -> { wholeScheme[0] = true; applyScheme.run(); });
        { /* 初始：依据当前配置展示，不触发 pending */
            boolean whole = wholeScheme[0];
            fieldTab.setTextColor(whole ? subColor() : textColor());
            wholeTab.setTextColor(whole ? textColor() : subColor());
            setTabEnabled(fieldTab, !whole);
            setTabEnabled(wholeTab, whole);
            fieldBox.setVisibility(whole ? android.view.View.GONE : android.view.View.VISIBLE);
            wholeBox.setVisibility(whole ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        /* ---- 应用按钮：改动先留在 pending，点此才保存 ---- */
        final android.widget.Button applyBtn = new android.widget.Button(this);
        applyBtn.setText(getString(R.string.apply));
        applyBtn.setTextSize(16);
        applyBtn.setAllCaps(false);
        applyBtn.setTextColor(0xFFFFFFFF);
        applyBtn.setBackgroundColor(accent());
        GradientDrawable applyBg = new GradientDrawable();
        applyBg.setCornerRadius(dp(10));
        applyBg.setColor(accent());
        applyBtn.setBackground(applyBg);
        applyBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        applyLp.topMargin = dp(20);
        applyBtn.setLayoutParams(applyLp);
        applyBtn.setOnClickListener(v -> {
            try {
                pushPending();
                android.widget.Toast.makeText(this, getString(R.string.applied),
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                android.widget.Toast.makeText(this,
                        getString(R.string.save_failed, t == null ? "?" : t.getMessage()),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        root.addView(applyBtn);

        /* ---- 启动 HTTP 服务 ---- */
        syncHttpServer();

        setContentView(scroll);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(getString(R.string.app_name));
        }
    }

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_DEBUG = 2;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_SETTINGS, 0, getString(R.string.menu_settings));
        menu.add(Menu.NONE, MENU_DEBUG, 1, getString(R.string.menu_target_resources));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (!pending.isEmpty()) {
                android.widget.Toast.makeText(this, getString(R.string.unsaved_changes),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            finish();
            return true;
        }
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new android.content.Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_DEBUG) {
            startActivity(new android.content.Intent(this, DebugActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!pending.isEmpty()) {
            android.widget.Toast.makeText(this, getString(R.string.unsaved_back),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBanner();
        syncHttpServer();
        previewTv.removeCallbacks(previewTicker);
        previewTv.post(previewTicker);
    }

    /** 供 flavor 工厂在服务绑定完成后刷新横幅（保持绝对实时）。 */
    public void refreshBanner() {
        runOnUiThread(this::updateBanner);
    }

    /** 供 flavor 工厂在服务绑定完成时调用：若编辑区曾以未绑定默认值构建，
     *  重建并回填真实配置。只执行一次；重建后再绑定则无需重来。 */
    public void refreshOnServiceBound() {
        runOnUiThread(() -> {
            if (!uiBuiltWithService && pendingBoundRebuild) {
                pendingBoundRebuild = false;
                uiBuiltWithService = true;
                store = ConfigStoreFactory.create(this);
                recreate();
            }
        });
    }

    /* 实时预览刷新：按配置的更新速度循环。文本 watcher 已实时写入 pending，点“应用”才落盘。 */
    private final Runnable previewTicker = new Runnable() {
        @Override
        public void run() {
            refreshPreview();
            previewTv.postDelayed(this, refreshIntervalMs());
        }
    };

    private long refreshIntervalMs() {
        float sec = cfg().refreshInterval;
        if (sec < 0.1f) sec = 0.1f;
        return (long) (sec * 1000);
    }

    private void pushPending() {
        if (!pending.isEmpty()) {
            store.write(this, pending);
            pending.clear();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        previewTv.removeCallbacks(previewTicker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        previewTv.removeCallbacks(previewTicker);
        updateBanner();
        stopHttpServer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHttpServer();
    }

    /* ------------------------------------------------------------------ */
    /* HTTP 服务管理                                                        */
    /* ------------------------------------------------------------------ */

    private void syncHttpServer() {
        stopHttpServer();
        Config c = cfg();
        if (c.exposeApi) {
            httpServer = new ConfigHttpServer(this, store, c.apiPort, c.apiKey, c.apiBindIp, c.apiScheme);
            httpServer.start();
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 配置读取 / 横幅                                                      */
    /* ------------------------------------------------------------------ */

    private Config cfg() {
        if (store == null) {
            return new Config(SlotMode.DEFAULT, "", SlotMode.DEFAULT, "", false, "", false, 8080, "", 1, true);
        }
        try {
            return store.load();
        } catch (Throwable t) {
            return new Config(SlotMode.DEFAULT, "", SlotMode.DEFAULT, "", false, "", false, 8080, "", 1, true);
        }
    }

    private String idxName(int idx) {
        switch (idx) {
            case SlotMode.SHOW: return SlotMode.MODE_SHOW;
            case SlotMode.HIDE: return SlotMode.MODE_HIDE;
            default: return SlotMode.MODE_DEFAULT;
        }
    }

    private int modeOf(int configMode) {
        return configMode == SlotMode.SHOW ? SlotMode.SHOW
                : configMode == SlotMode.HIDE ? SlotMode.HIDE : SlotMode.DEFAULT;
    }

    private void updateBanner() {
        if (store == null || bannerTv == null) return;
        boolean active = false;
        try {
            active = store.isActive();
        } catch (Throwable ignored) {
        }
        if (active) {
            bannerTv.setBackgroundColor(0xFF1B5E20);
            bannerTv.setTextColor(0xFFFFFFFF);
            bannerTv.setText(getString(R.string.banner_active));
        } else {
            bannerTv.setBackgroundColor(0xFFB71C1C);
            bannerTv.setTextColor(0xFFFFFFFF);
            bannerTv.setText(getString(R.string.banner_inactive));
        }
    }

    /** 计算当前应显示的文案（考虑 pending 未写入的临时值）。任何求值异常都回退为提示文案，绝不带崩 UI。 */
    private String computePreviewText() {
        try {
            Boolean enabled = boolFor(pending, Config.KEY_ENABLED, cfg().enabled);
            if (enabled == null || !enabled) {
                return getString(R.string.preview_disabled);
            }
            String raw = rawTemplate();
            if (raw == null) {
                return getString(R.string.preview_default);
            }
            Boolean expressions = boolFor(pending, Config.KEY_EXPRESSIONS, cfg().expressions);
            if (expressions == null || !expressions) {
                // 表达式注入未开启：不注入，原文直出（预览与实际一致）。
                return raw;
            }
            ExpressionContext ctx = new PreviewExpressionContext(getApplicationContext());
            return ExpressionEngine.evaluate(raw, ctx, previewOverrides());
        } catch (Throwable t) {
            return getString(R.string.preview_error, t.getClass().getSimpleName());
        }
    }

    /** 当前生效的模板原始文本（整句替换或拼装结果的原文，未求值）；无干预返回 null。 */
    private String rawTemplate() {
        Config c = cfg();
        String ownerText = stringFor(pending, Config.KEY_OWNER_TEXT, c.ownerText);
        String vpnText = stringFor(pending, Config.KEY_VPN_TEXT, c.vpnText);
        String wholeText = stringFor(pending, Config.KEY_WHOLE_TEXT,
                c.wholeEnabled() ? c.wholeText : "");
        Boolean wholeBool = boolFor(pending, Config.KEY_REWRITE_WHOLE,
                c.rewriteWhole && !wholeText.isEmpty());

        boolean wholeEnabled = wholeBool != null && wholeBool && !wholeText.trim().isEmpty();
        if (wholeEnabled) {
            return wholeText;
        }

        /* 合并 pending 中的模式选择 */
        int ownerMode = intModeFor(pending, Config.KEY_OWNER_MODE, c.ownerMode);
        int vpnMode = intModeFor(pending, Config.KEY_VPN_MODE, c.vpnMode);
        // 预览用系统原值作为占位（实际运行时由 args 提供）
        return ComposePrompt.assemble(ownerMode, ownerText, "系统Owner",
                vpnMode, vpnText, "系统VPN");
    }

    /** 预览时覆写的变量：与 {@link #rawTemplate()} 的占位一致，让 ${owner}/${vpn} 可预览。 */
    private Map<String, String> previewOverrides() {
        Map<String, String> overrides = new HashMap<>(4);
        overrides.put("owner", "系统Owner");
        overrides.put("vpn", "系统VPN");
        return overrides;
    }

    private int intModeFor(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof String) return SlotMode.from((String) v);
        return def;
    }

    private String stringFor(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : def;
    }

    private static Boolean boolFor(Map<String, Object> map, String key, Boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }

    private void refreshPreview() {
        if (previewTv == null) return;
        String text = computePreviewText();
        previewTv.setTextColor(accent());
        previewTv.setText(getString(R.string.preview_line, text));
    }

    /* ------------------------------------------------------------------ */
    /* UI helpers — 深浅色自动跟随系统                                      */
    /* ------------------------------------------------------------------ */

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int accent() { return isDark() ? 0xFF8AB4F8 : 0xFF1A73E8; }
    private int textColor() { return isDark() ? 0xFFE1E1E1 : 0xFF202124; }
    private int subColor() { return isDark() ? 0xFF9AA0A6 : 0xFF5F6368; }
    private int dimColor() { return isDark() ? 0xFF5F6368 : 0xFF9AA0A6; }
    private int bgColor() { return isDark() ? 0xFF121212 : 0xFFFFFFFF; }

    private void title(LinearLayout root, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(24);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(textColor());
        root.addView(tv);
    }

    private void subtitle(LinearLayout root, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setLineSpacing(0, 1.25f);
        tv.setTextColor(subColor());
        tv.setTextSize(13);
        tv.setPadding(0, dp(4), 0, 0);
        root.addView(tv);
    }

    private void section(LinearLayout root, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(accent());
        tv.setPadding(0, dp(20), 0, dp(4));
        root.addView(tv);
    }

    /** 开关选中态配色：开启时轨道绿色 + 白色按钮，关闭时恢复系统默认灰。 */
    private void applySwitchLook(android.widget.Switch sw) {
        if (android.os.Build.VERSION.SDK_INT >= 21 && sw != null) {
            if (sw.isChecked()) {
                sw.setTrackTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
                sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            } else {
                sw.setTrackTintList(null);
                sw.setThumbTintList(null);
            }
        }
    }

    /** 编辑方案切换用的圆角标签（修改字段 / 全部修改）。 */
    private TextView schemeTab(LinearLayout bar, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        tv.setClickable(true);
        tv.setBackground(rippleBg());
        bar.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return tv;
    }

    private android.graphics.drawable.Drawable rippleBg() {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), null);
        }
        return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
    }

    /** 标签激活态（选中项文字用主题色并加下划线）。 */
    private void setTabEnabled(TextView tv, boolean active) {
        tv.setTextColor(active ? accent() : subColor());
        tv.setPaintFlags(active
                ? tv.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                : tv.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
    }

    /** 一行：标题在上，下方文本框 + 右侧显示情况下拉（默认 / 显示 / 隐藏）。 */
    private void modeInputRow(LinearLayout root, String title, String sub,
                              int mode, String text, OnIdx onMode, OnText onText) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(4), dp(6), dp(4), dp(6));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(textColor());
        t.setTextSize(16);
        wrapper.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(subColor());
            s.setTextSize(12);
            wrapper.addView(s);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(6), 0, 0);

        final EditText et = new EditText(this);
        et.setText(text);
        et.setSingleLine(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(1);
        et.setHint(getString(R.string.input_hint, title));
        et.setTextColor(textColor());
        et.setHintTextColor(subColor());
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(dp(8));
        etBg.setStroke(dp(1), accent());
        et.setBackground(etBg);
        et.setPadding(dp(10), dp(6), dp(10), dp(6));

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                try {
                    onText.on(s == null ? "" : s.toString().trim());
                } catch (Throwable ignored) {
                }
            }
        });

        final Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.mode_default), getString(R.string.mode_show), getString(R.string.mode_hide)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        final int initPos = modeOf(mode);
        // “隐藏”模式下文本框不再生效，仅视觉置灰（保留可编辑，不清除、不改逻辑）。
        Runnable applyVisual = () -> {
            int pos = spinner.getSelectedItemPosition();
            boolean gray = pos == SlotMode.HIDE;
            et.setTextColor(gray ? subColor() : textColor());
            et.setHintTextColor(gray ? dimColor() : subColor());
            etBg.setStroke(dp(1), gray ? subColor() : accent());
            et.setAlpha(gray ? 0.5f : 1.0f);
        };
        // 先 setSelection，再注册 listener；注册时 AdapterView 会同步回调一次当前项，
        // 与初始位置相同的回调代表“初始化/布局”，只有初次回调更新视觉，不写入 pending。
        spinner.setSelection(initPos);
        applyVisual.run();
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View v,
                                       int position, long id) {
                applyVisual.run();
                if (position == initPos) {
                    return;
                }
                try {
                    onMode.on(position);
                } catch (Throwable ignored) {
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        row.addView(et, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(row);
        root.addView(wrapper);
    }
}