package io.github.fnyoat.tipismine.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.fnyoat.tipismine.hook.Config;
import io.github.fnyoat.tipismine.hook.ConfigAccess;
import io.github.fnyoat.tipismine.R;
import io.github.fnyoat.tipismine.hook.OwnershipKeys;
import io.github.fnyoat.tipismine.hook.ScanReporter;

/**
 * 目标资源列表（调试）页：查看/管理模块实际匹配 / 已检测到的归属提示资源名。
 *
 * <p>匹配优先级（见 {@code PromptRewriter}）：
 * 内置 KEYS ∪ 自定义名单 → 直接应用；内容扫描开启时（默认）对名单外的资源
 * 做自适应内容检测兜底，命中的 key 会通过日志回报，用户可在此手动收录。
 *
 * <p>修改经 pending 在 onStop 落盘（与设置页一致）。含醒目警告：
 * 若你不了解这些名字的含义，请不要修改。
 */
public class DebugActivity extends Activity {

    private final Map<String, Object> pending = new LinkedHashMap<>();
    private ConfigAccess store;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private String deviceInfoCopyText;

    /** 内容扫描开关 / 自定义名单编辑框 / 扫描结果展示（供扫描广播回调刷新 UI）。 */
    private android.widget.Switch scanSwitch;
    private EditText keysEt;
    private TextView scanResultTv;
    private boolean scanReceiverRegistered;
    /** 屏蔽 onCreate 里 setChecked 首次回调；置 true 后才响应用户/广播的切换。 */
    private boolean scanUserTouched;

    /** 扫描命中广播：hook 侧把模板命中但不在名单里的资源名回传，这里自动录入。 */
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            onScanHit(c, i);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        ConfigStoreFactory.onActivityStart(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = ConfigStoreFactory.create(this);

        // 生成扫描回传认证令牌（hook 侧用它附在广播里，防伪造注入）。
        try {
            if (cfg().scanToken.isEmpty()) {
                pending.put(Config.KEY_SCAN_TOKEN, generateToken());
                pushPending();
            }
        } catch (Throwable ignored) {
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);

        /* ---- 警告横幅 ---- */
        TextView warn = new TextView(this);
        warn.setText(getString(R.string.debug_warn));
        warn.setTextSize(14);
        warn.setTypeface(Typeface.DEFAULT_BOLD);
        warn.setTextColor(0xFFFFFFFF);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        warn.setBackgroundColor(0xFFB71C1C);
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(warn, warnLp);

        addDeviceInfo(root);

        subtitle(root, getString(R.string.debug_intro));

        java.util.Set<String> activeKeyNames = systemUiActiveKeyNames();
        addResourceStatus(root, activeKeyNames);

        /* ---- 内容自适应检测开关 ---- */
        section(root, getString(R.string.scan_section));
        scanSwitch = new android.widget.Switch(this);
        scanSwitch.setText(getString(R.string.scan_switch));
        scanSwitch.setTextSize(16);
        scanSwitch.setTextColor(textColor());
        scanSwitch.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = dp(8);
        root.addView(scanSwitch, scanLp);
        scanSwitch.setChecked(cfg().contentScan);
        scanUserTouched = true; // 上面的首次 setChecked 回调已消费，之后都当作真实切换
        scanSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!scanUserTouched) {
                return; // 忽略 setChecked 触发的首次回调（含自动开启）
            }
            pending.put(Config.KEY_CONTENT_SCAN, isChecked);
            if (isChecked) {
                // 重新打开扫描：旧扫描结果被新结果覆盖
                pending.put(Config.KEY_SCAN_RESULTS, "");
            }
            pushPending();
            if (isChecked) {
                registerScanReceiver();
            } else {
                unregisterScanReceiver();
            }
            refreshScanResults();
        });
        subtitle(root, getString(R.string.scan_explain));

        /* ---- 扫描结果（自动捕获到的资源名） ---- */
        section(root, getString(R.string.scan_result_section));
        scanResultTv = new TextView(this);
        scanResultTv.setTextSize(13);
        scanResultTv.setTextColor(textColor());
        scanResultTv.setTypeface(Typeface.MONOSPACE);
        scanResultTv.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(scanResultTv);
        refreshScanResults();
        subtitle(root, getString(R.string.scan_auto_off_note));
        if (cfg().contentScan) {
            registerScanReceiver();
        }

        /* ---- 自定义名单编辑 ---- */
        section(root, getString(R.string.custom_keys_section));
        keysEt = new EditText(this);
        keysEt.setText(cfg().customKeys);
        keysEt.setSingleLine(false);
        keysEt.setMinLines(5);
        keysEt.setGravity(Gravity.TOP | Gravity.START);
        keysEt.setHint(getString(R.string.custom_keys_hint));
        keysEt.setTextColor(textColor());
        keysEt.setHintTextColor(subColor());
        keysEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(dp(8));
        etBg.setStroke(dp(1), accent());
        keysEt.setBackground(etBg);
        keysEt.setPadding(dp(10), dp(6), dp(10), dp(6));
        keysEt.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        keysEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                pending.put(Config.KEY_CUSTOM_KEYS,
                        s == null ? "" : s.toString().trim());
            }
        });
        root.addView(keysEt);

        android.widget.Button saveKeys = new android.widget.Button(this);
        saveKeys.setText(getString(R.string.save_keys));
        saveKeys.setTextSize(16);
        saveKeys.setAllCaps(false);
        saveKeys.setTextColor(0xFFFFFFFF);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setCornerRadius(dp(10));
        saveBg.setColor(accent());
        saveKeys.setBackground(saveBg);
        saveKeys.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(8);
        root.addView(saveKeys, saveLp);
        saveKeys.setOnClickListener(v -> {
            String raw = keysEt.getText() == null ? "" : keysEt.getText().toString().trim();
            pending.put(Config.KEY_CUSTOM_KEYS, raw);
            try {
                pushPending();
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, getString(R.string.save_failed,
                        t.getMessage() == null ? "?" : t.getMessage()),
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            android.widget.Toast.makeText(this, getString(R.string.saved), android.widget.Toast.LENGTH_SHORT).show();
        });
        subtitle(root, getString(R.string.custom_keys_note));

        /* ---- 内置名单只读展示（按来源系统分组表格） ---- */
        section(root, getString(R.string.builtin_section));
        subtitle(root, getString(R.string.builtin_sub));

        String[][] builtin = OwnershipKeys.builtinKeyEntries();

        // 按 k[1]（来源系统）分组，保持首次出现顺序。
        java.util.LinkedHashMap<String, java.util.List<String>> groups = new java.util.LinkedHashMap<>();
        for (String[] k : builtin) {
            java.util.List<String> list = groups.get(k[1]);
            if (list == null) {
                list = new java.util.ArrayList<>();
                groups.put(k[1], list);
            }
            list.add(k[0]);
        }
        for (java.util.Map.Entry<String, java.util.List<String>> g : groups.entrySet()) {
            section(root, g.getKey());
            for (String keyName : g.getValue()) {
                boolean active = OwnershipKeys.isActive(keyName, activeKeyNames);
                TextView tv = new TextView(this);
                tv.setText("· " + keyName);
                tv.setTextSize(active ? 14 : 12);
                tv.setTextColor(active ? 0xFFD4A017 : subColor());
                tv.setTypeface(Typeface.MONOSPACE, active ? Typeface.BOLD : Typeface.NORMAL);
                tv.setPadding(dp(8), dp(2), dp(8), dp(2));
                root.addView(tv);
            }
        }
        subtitle(root, getString(R.string.builtin_note));

        setContentView(scroll);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(getString(R.string.menu_target_resources));
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (!pending.isEmpty()) {
                pushPending();
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 与主页 Setup 一致：未点“保存自定义名单”的其它 pending（如开关）在 onStop 落盘。 */
    @Override
    protected void onStop() {
        super.onStop();
        if (!pending.isEmpty()) {
            pushPending();
        }
    }

    private void pushPending() {
        if (!pending.isEmpty()) {
            store.write(this, pending);
            pending.clear();
        }
    }

    /** 扫描命中广播处理：校验令牌 → 填入自定义名单 + 追加扫描结果 → 自动关闭扫描。 */
    private void onScanHit(Context c, Intent i) {
        try {
            String name = i.getStringExtra(ScanReporter.EXTRA_NAME);
            String token = i.getStringExtra(ScanReporter.EXTRA_TOKEN);
            if (name == null || name.isEmpty()) {
                return;
            }
            Config cfg = cfg();
            if (token == null || token.isEmpty() || cfg.scanToken.isEmpty()
                    || !token.equals(cfg.scanToken)) {
                return; // 防伪
            }
            if (OwnershipKeys.matchesCustom(name, cfg.customKeys)) {
                return; // 已在自定义名单，无需重复录入
            }
            String custom = cfg.customKeys.trim();
            String results = cfg.scanResults.trim();
            custom = custom.isEmpty() ? name : custom + "\n" + name;
            results = results.isEmpty() ? name : results + "\n" + name;
            pending.put(Config.KEY_CUSTOM_KEYS, custom);
            pending.put(Config.KEY_SCAN_RESULTS, results);
            pushPending();
            if (keysEt != null) {
                keysEt.setText(custom);
                keysEt.setSelection(custom.length());
            }
            // 命中即自动关闭扫描；重新打开时会清空旧结果重新捕获。
            pending.put(Config.KEY_CONTENT_SCAN, false);
            pushPending();
            if (scanSwitch != null) {
                scanSwitch.setChecked(false);
            }
            unregisterScanReceiver();
            refreshScanResults();
            android.widget.Toast.makeText(this, getString(R.string.scan_captured),
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // 广播处理绝不拖垮调试页
        }
    }

    private void registerScanReceiver() {
        if (scanReceiverRegistered) {
            return;
        }
        try {
            IntentFilter f = new IntentFilter(ScanReporter.ACTION_SCAN_HIT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(scanReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(scanReceiver, f);
            }
            scanReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void unregisterScanReceiver() {
        if (!scanReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(scanReceiver);
        } catch (Throwable ignored) {
        }
        scanReceiverRegistered = false;
    }

    private void refreshScanResults() {
        if (scanResultTv == null) {
            return;
        }
        String results = cfg().scanResults.trim();
        scanResultTv.setText(results.isEmpty()
                ? getString(R.string.scan_result_empty)
                : results);
    }

    /** 生成随机认证令牌（长 hex），防其它应用伪造扫描命中广播。 */
    private String generateToken() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[24];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder(48);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        unregisterScanReceiver();
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */
    /* 配置读取 / UI helpers                                               */
    /* ------------------------------------------------------------------ */

    private Config cfg() {
        if (store == null) {
            return new Config(0, "", 0, "", false, "", false, 8080, "", 1, true);
        }
        try {
            return store.load();
        } catch (Throwable t) {
            return new Config(0, "", 0, "", false, "", false, 8080, "", 1, true);
        }
    }

    /** 设备 / SystemUI 信息：系统、SystemUI 版本、SystemUI APK 短 hash。长按1秒复制全部信息。 */
    private void addDeviceInfo(LinearLayout root) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        section(container, getString(R.string.device_info_section));

        StringBuilder copy = new StringBuilder();

        String system = "Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n" + Build.MANUFACTURER + " " + Build.MODEL
                + "\n" + Build.DISPLAY;
        infoRow(container, getString(R.string.info_system), system);
        copy.append(getString(R.string.info_system)).append(": ").append(system).append("\n");

        PackageInfo pi = null;
        try {
            pi = getPackageManager().getPackageInfo("com.android.systemui", 0);
        } catch (Throwable ignored) {
        }
        if (pi != null) {
            String version = pi.versionName + " (" + versionCodeOf(pi) + ")";
            infoRow(container, getString(R.string.info_sysui_version), version);
            copy.append(getString(R.string.info_sysui_version)).append(": ").append(version).append("\n");
            final String apkPath = pi.applicationInfo == null ? null : pi.applicationInfo.sourceDir;
            if (apkPath == null || apkPath.isEmpty()) {
                infoRow(container, getString(R.string.info_sysui_apk), getString(R.string.not_found));
                copy.append(getString(R.string.info_sysui_apk)).append(": (not found)\n");
            } else {
                final TextView pathTv = infoRow(container, getString(R.string.info_sysui_apk), apkPath);
                final TextView hashTv = infoRow(container, getString(R.string.info_apk_hash), getString(R.string.computing));
                computeApkShortHash(apkPath, hashTv, pathTv);
                copy.append(getString(R.string.info_sysui_apk)).append(": ").append(apkPath).append("\n");
                copy.append(getString(R.string.info_apk_hash)).append(": computing…\n");
            }
        } else {
            infoRow(container, getString(R.string.info_sysui_version), getString(R.string.cannot_read));
            copy.append(getString(R.string.info_sysui_version)).append(": (cannot read)\n");
        }

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.long_press_copy));
        hint.setTextSize(11);
        hint.setTextColor(subColor());
        hint.setPadding(dp(4), dp(4), dp(4), dp(2));
        container.addView(hint);

        deviceInfoCopyText = copy.toString().trim();

        root.addView(container);

        container.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    longPressRunnable = () -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null && deviceInfoCopyText != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("device_info", deviceInfoCopyText));
                            android.widget.Toast.makeText(DebugActivity.this,
                                    getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    };
                    longPressHandler.postDelayed(longPressRunnable, 1000);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return false;
        });
    }

    /** 后台线程计算 apk 文件的短 hash（前 8 位十六进制），完成后回主线程更新。 */
    private void computeApkShortHash(final String apkPath, final TextView hashTv,
                                     final TextView apkTv) {
        final Thread t = new Thread(() -> {
            String hash = apkShortHash(apkPath);
            runOnUiThread(() -> {
                if (hash == null) {
                    hashTv.setText(getString(R.string.compute_failed));
                } else {
                    hashTv.setText(hash);
                    if (apkTv != null) {
                        apkTv.setText(apkPath + "  [" + hash + "]");
                    }
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /** 计算文件 SHA-1 短 hash；失败返回 null。 */
    private static String apkShortHash(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;
        try (InputStream is = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 资源状态提示：红字=资源未命中无法使用，绿字=正常+调试开关。 */
    private void addResourceStatus(LinearLayout root, java.util.Set<String> activeKeyNames) {
        if (activeKeyNames == null || activeKeyNames.isEmpty()) {
            // 资源未命中 → 自动开启内容扫描，让模块按文案特征捕获新 key。
            if (!cfg().contentScan) {
                pending.put(Config.KEY_CONTENT_SCAN, true);
                pushPending();
            }
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.resource_miss));
            tv.setTextColor(0xFFEF5350);
            tv.setTextSize(13);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setPadding(dp(4), dp(8), dp(4), dp(4));
            root.addView(tv);
        } else {
            // 资源全部命中 → 自动关闭内容扫描（无需兜底）
            if (cfg().contentScan) {
                pending.put(Config.KEY_CONTENT_SCAN, false);
                pushPending();
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(this);
            tv.setText(getString(R.string.resource_ok));
            tv.setTextColor(0xFF66BB6A);
            tv.setTextSize(13);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(tv);

            // 调试开关：关闭后禁止检测逻辑，固化资源名配置
            final android.widget.Switch debugSwitch = new android.widget.Switch(this);
            debugSwitch.setText(getString(R.string.debug_label));
            debugSwitch.setTextSize(12);
            debugSwitch.setTextColor(accent());
            debugSwitch.setChecked(!cfg().debugPageRemoved);
            debugSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                pending.put(Config.KEY_DEBUG_PAGE_REMOVED, !isChecked);
                if (!isChecked) {
                    pending.put(Config.KEY_CONTENT_SCAN, false);
                    pending.put(Config.KEY_CUSTOM_KEYS, "");
                    pending.put(Config.KEY_SCAN_RESULTS, "");
                    if (scanSwitch != null) {
                        scanSwitch.setChecked(false);
                    }
                    unregisterScanReceiver();
                }
                pushPending();
            });
            row.addView(debugSwitch);

            root.addView(row);
            subtitle(root, getString(R.string.debug_switch_note));
        }
    }

    /** @return 单行（标题 + 值）信息条目。 */
    private TextView infoRow(LinearLayout root, String title, String value) {
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.info_row, title, value));
        tv.setTextSize(13);
        tv.setTextColor(textColor());
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(0, 1.2f);
        tv.setPadding(dp(4), dp(2), dp(4), dp(2));
        root.addView(tv);
        return tv;
    }

    private static long versionCodeOf(PackageInfo pi) {
        return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode()
                : (pi.versionCode & 0xffffffffL);
    }

    /** 读取 SystemUI 的 Resources，判断内置 key 在本机是否真实存在（完美命中）。 */
    private java.util.Set<String> systemUiActiveKeyNames() {
        try {
            android.content.res.Resources sysuiRes =
                    getPackageManager().getResourcesForApplication("com.android.systemui");
            return OwnershipKeys.entryNamesToSet(OwnershipKeys.buildActiveEntries(sysuiRes));
        } catch (Throwable t) {
            return java.util.Collections.emptySet();
        }
    }

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
    private int bgColor() { return isDark() ? 0xFF121212 : 0xFFFFFFFF; }

    private void section(LinearLayout root, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(accent());
        tv.setPadding(0, dp(20), 0, dp(4));
        root.addView(tv);
    }

    private void subtitle(LinearLayout root, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setLineSpacing(0, 1.25f);
        tv.setTextColor(subColor());
        tv.setTextSize(13);
        tv.setPadding(0, dp(4), 0, dp(8));
        root.addView(tv);
    }
}