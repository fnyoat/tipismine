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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.fnyoat.tipismine.R;
import io.github.fnyoat.tipismine.hook.Config;
import io.github.fnyoat.tipismine.hook.ConfigAccess;
import io.github.fnyoat.tipismine.hook.LockScreenPrefs;
import io.github.fnyoat.tipismine.hook.SlotMode;

/**
 * 锁屏专属配置页（"分离修改控制中心与锁屏"开启后进入）。
 *
 * <p>与主页 {@link ConfigActivity} 如出一辙，但只含编辑区与方案切换（修改字段 / 全部修改）；
 * 无 banner、实时预览、HTTP 服务——这些跟随主页 / 设置。Owner / VPN 的模式下拉
 * 在"默认 / 显示 / 隐藏"之上新增默认项"跟随主页"（跟随主页 = 沿用主页该槽位配置）。
 * 锁屏整段替换为独立文本，不继承主页。
 */
public class LockScreenConfigActivity extends Activity {

    interface OnMode { void on(int index); }
    interface OnText { void on(String text); }

    private final Map<String, Object> pending = new LinkedHashMap<>();
    private ConfigAccess store;

    @Override
    protected void onStart() {
        super.onStart();
        ConfigStoreFactory.onActivityStart(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = ConfigStoreFactory.create(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);

        title(root, getString(R.string.lock_settings_title));
        subtitle(root, getString(R.string.config_main_subtitle));

        final LockScreenPrefs base = lockPrefs();

        /* 整段替换与字段编辑二选一（与主页一致）。 */
        final boolean[] wholeScheme = {base.wholeEnabled()};
        LinearLayout schemeBar = new LinearLayout(this);
        schemeBar.setOrientation(LinearLayout.HORIZONTAL);
        final TextView fieldTab = schemeTab(schemeBar, getString(R.string.scheme_field_tab));
        final TextView wholeTab = schemeTab(schemeBar, getString(R.string.scheme_whole_tab));

        LinearLayout fieldBox = new LinearLayout(this);
        fieldBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout wholeBox = new LinearLayout(this);
        wholeBox.setOrientation(LinearLayout.VERTICAL);

        /* ---- 方案A：修改字段（owner / vpn，各含"跟随主页"） ---- */
        section(fieldBox, getString(R.string.owner_section));
        lockModeInputRow(fieldBox, getString(R.string.owner_title), null,
                base.ownerFollow, base.ownerModeInt(), base.ownerText,
                iv -> applyOwnerMode(iv),
                s -> pending.put(Config.KEY_LOCK_OWNER_TEXT, s));
        subtitle(fieldBox, getString(R.string.owner_example));

        section(fieldBox, getString(R.string.vpn_title));
        lockModeInputRow(fieldBox, getString(R.string.vpn_title), null,
                base.vpnFollow, base.vpnModeInt(), base.vpnText,
                iv -> applyVpnMode(iv),
                s -> pending.put(Config.KEY_LOCK_VPN_TEXT, s));
        subtitle(fieldBox, getString(R.string.vpn_example));

        /* ---- 方案B：全部修改（整句替换，独立文本） ---- */
        section(wholeBox, getString(R.string.whole_section));
        final EditText wholeEt = new EditText(this);
        wholeEt.setText(base.rewriteWhole ? base.wholeText : "");
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
                pending.put(Config.KEY_LOCK_WHOLE_TEXT, s == null ? "" : s.toString().trim());
            }
        });
        wholeBox.addView(wholeEt);
        subtitle(wholeBox, getString(R.string.whole_example));

        root.addView(schemeBar);
        root.addView(fieldBox);
        root.addView(wholeBox);

        Runnable applyScheme = () -> {
            boolean whole = wholeScheme[0];
            pending.put(Config.KEY_LOCK_REWRITE_WHOLE, whole);
            fieldTab.setTextColor(whole ? subColor() : textColor());
            wholeTab.setTextColor(whole ? textColor() : subColor());
            setTabEnabled(fieldTab, !whole);
            setTabEnabled(wholeTab, whole);
            fieldBox.setVisibility(whole ? android.view.View.GONE : android.view.View.VISIBLE);
            wholeBox.setVisibility(whole ? android.view.View.VISIBLE : android.view.View.GONE);
        };
        fieldTab.setOnClickListener(v -> { wholeScheme[0] = false; applyScheme.run(); });
        wholeTab.setOnClickListener(v -> { wholeScheme[0] = true; applyScheme.run(); });
        { // 初始
            boolean whole = wholeScheme[0];
            fieldTab.setTextColor(whole ? subColor() : textColor());
            wholeTab.setTextColor(whole ? textColor() : subColor());
            setTabEnabled(fieldTab, !whole);
            setTabEnabled(wholeTab, whole);
            fieldBox.setVisibility(whole ? android.view.View.GONE : android.view.View.VISIBLE);
            wholeBox.setVisibility(whole ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        /* ---- 应用按钮 ---- */
        final android.widget.Button applyBtn = new android.widget.Button(this);
        applyBtn.setText(getString(R.string.apply));
        applyBtn.setTextSize(16);
        applyBtn.setAllCaps(false);
        applyBtn.setTextColor(0xFFFFFFFF);
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

        setContentView(scroll);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(getString(R.string.lock_settings_title));
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (!pending.isEmpty()) {
                android.util.Log.w("TipIsMine", "lock-back-blocked pending=" + pending.keySet()
                        + " whole=" + pending.get(Config.KEY_LOCK_WHOLE_TEXT));
                android.widget.Toast.makeText(this, getString(R.string.unsaved_changes),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!pending.isEmpty()) {
            android.util.Log.w("TipIsMine", "lock-back-blocked pending=" + pending.keySet()
                    + " whole=" + pending.get(Config.KEY_LOCK_WHOLE_TEXT));
            android.widget.Toast.makeText(this, getString(R.string.unsaved_back),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!pending.isEmpty()) {
            store.write(this, pending);
            pending.clear();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 锁屏配置读写                                                        */
    /* ------------------------------------------------------------------ */

    /** 读取当前锁屏偏好；未分离 / 暂不可用时不退出默认（全跟随主页、整段关闭）。 */
    private LockScreenPrefs lockPrefs() {
        try {
            Config c = store.load();
            return c.lockscreen != null ? c.lockscreen
                    : new LockScreenPrefs(true, SlotMode.MODE_DEFAULT, "",
                    true, SlotMode.MODE_DEFAULT, "", false, "");
        } catch (Throwable t) {
            return new LockScreenPrefs(true, SlotMode.MODE_DEFAULT, "",
                    true, SlotMode.MODE_DEFAULT, "", false, "");
        }
    }

    private void pushPending() {
        if (!pending.isEmpty()) {
            store.write(this, pending);
            pending.clear();
        }
    }

    /** 下拉索引 → 绝不覆盖"跟随主页"总开关的量：0 跟随主页 / 1 默认 / 2 显示 / 3 隐藏。 */
    private void applyOwnerMode(int idx) {
        pending.put(Config.KEY_LOCK_OWNER_FOLLOW, idx == 0);
        if (idx > 0) pending.put(Config.KEY_LOCK_OWNER_MODE, modeName(idx));
    }

    private void applyVpnMode(int idx) {
        pending.put(Config.KEY_LOCK_VPN_FOLLOW, idx == 0);
        if (idx > 0) pending.put(Config.KEY_LOCK_VPN_MODE, modeName(idx));
    }

    private static String modeName(int idx) {
        switch (idx) {
            case 2: return SlotMode.MODE_SHOW;
            case 3: return SlotMode.MODE_HIDE;
            default: return SlotMode.MODE_DEFAULT;
        }
    }

    /** 现有三态模式值 → 带"跟随主页"的下拉索引。 */
    private static int modeIdx(boolean follow, int mode) {
        if (follow) return 0;
        return mode == SlotMode.SHOW ? 2 : mode == SlotMode.HIDE ? 3 : 1;
    }

    /* ------------------------------------------------------------------ */
    /* UI helpers（与 ConfigActivity 同款）                                */
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

    private void setTabEnabled(TextView tv, boolean active) {
        tv.setTextColor(active ? accent() : subColor());
        tv.setPaintFlags(active
                ? tv.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                : tv.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
    }

    /** 锁屏版行：标题 + 文本输入框 + 四态下拉（跟随主页/默认/显示/隐藏）。 */
    private void lockModeInputRow(LinearLayout root, String title, String sub,
                                  boolean follow, int mode, String text,
                                  OnMode onMode, OnText onText) {
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
        et.setText(follow ? "" : text);
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
                getString(R.string.mode_follow_main),
                getString(R.string.mode_default),
                getString(R.string.mode_show),
                getString(R.string.mode_hide)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        final int initPos = modeIdx(follow, mode);
        // “跟随主页 / 隐藏”模式下文本框不再生效，仅视觉置灰（保留可编辑，不清除、不改逻辑）。
        Runnable applyVisual = () -> {
            int pos = spinner.getSelectedItemPosition();
            boolean gray = pos == 0 || pos == 3;
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
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        row.addView(et, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(row);
        root.addView(wrapper);
    }
}