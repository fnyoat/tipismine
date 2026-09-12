package io.github.fnyoat.tipismine.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.fnyoat.tipismine.hook.Config;
import io.github.fnyoat.tipismine.hook.ConfigAccess;
import io.github.fnyoat.tipismine.R;

/**
 * 设置页：更新速度 / 网络 API（开关 / 端口 / API Key）/ 表达式帮助。
 * 由 {@link ConfigActivity} 右上角“设置”菜单进入；修改通过 pending 在 onStop 落盘，
 * HTTP 服务开关随返回主页面时的 {@link ConfigActivity#onResume} 对齐。
 */
public class SettingsActivity extends Activity {

    interface OnIdx { void on(int index); }
    interface OnText { void on(String text); }

    private final Map<String, Object> pending = new LinkedHashMap<>();
    private ConfigAccess store;
    private ListItem apiPortItem, apiBindIpItem, apiSchemeItem, apiKeyItem;

    private void applyApiVisibility(boolean show) {
        int vis = show ? android.view.View.VISIBLE : android.view.View.GONE;
        if (apiPortItem != null) apiPortItem.setVisibility(vis);
        if (apiBindIpItem != null) apiBindIpItem.setVisibility(vis);
        if (apiSchemeItem != null) apiSchemeItem.setVisibility(vis);
        if (apiKeyItem != null) apiKeyItem.setVisibility(vis);
    }

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
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);

        section(root, getString(R.string.lock_section));
        ListItem separate = new ListItem(this);
        separate.fillSwitch(getString(R.string.separate_lock_title),
                getString(R.string.separate_lock_sub),
                cfg().lockscreen != null,
                idx -> pending.put(Config.KEY_SEPARATE_LOCKSCREEN, idx == 1));
        // 点条目：进入锁屏专属配置页（分离未开启时给出提示）。
        separate.setOnClick(() -> {
            Object pendingVal = pending.get(Config.KEY_SEPARATE_LOCKSCREEN);
            boolean on = pendingVal instanceof Boolean ? (Boolean) pendingVal
                    : cfg().lockscreen != null;
            if (!on) {
                android.widget.Toast.makeText(this,
                        getString(R.string.lock_settings_sub),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new android.content.Intent(this, LockScreenConfigActivity.class));
        });
        root.addView(separate);

        section(root, getString(R.string.expressions_section));
        ListItem exprToggle = new ListItem(this);
        exprToggle.fillSwitch(getString(R.string.expressions_toggle_title),
                getString(R.string.expressions_toggle_sub),
                cfg().expressions,
                idx -> pending.put(Config.KEY_EXPRESSIONS, idx == 1));
        root.addView(exprToggle);

        section(root, getString(R.string.set_network_section));
        ListItem speed = new ListItem(this);
        speed.fill(getString(R.string.speed_title), getString(R.string.speed_sub), null);
        speed.setOnClick(() -> speedDialog(speed));
        speed.setValueText(formatInterval(cfg().refreshInterval));
        root.addView(speed);

        ListItem apiToggle = new ListItem(this);
        apiToggle.fill(getString(R.string.api_toggle_title), getString(R.string.api_toggle_sub),
                new String[]{getString(R.string.api_off), getString(R.string.api_on)});
        apiToggle.setSelection(cfg().exposeApi ? 1 : 0);
        apiToggle.setOnSelected(idx -> {
            pending.put(Config.KEY_EXPOSE_API, idx == 1);
            applyApiVisibility(idx == 1);
        });
        root.addView(apiToggle);

        apiPortItem = new ListItem(this);
        apiPortItem.fill(getString(R.string.port_title), getString(R.string.port_sub), null);
        apiPortItem.setOnClick(() -> textDialog(getString(R.string.port_title), String.valueOf(cfg().apiPort),
                s -> {
                    try {
                        pending.put(Config.KEY_API_PORT, Integer.parseInt(s));
                    } catch (NumberFormatException ignored) {
                    }
                }));
        root.addView(apiPortItem);

        apiBindIpItem = new ListItem(this);
        apiBindIpItem.fill(getString(R.string.bind_ip_title), getString(R.string.bind_ip_sub), null);
        apiBindIpItem.setOnClick(() -> textDialog(getString(R.string.bind_ip_title), cfg().apiBindIp,
                s -> pending.put(Config.KEY_API_BIND_IP, s)));
        root.addView(apiBindIpItem);

        apiSchemeItem = new ListItem(this);
        apiSchemeItem.fill(getString(R.string.scheme_title), getString(R.string.scheme_sub),
                new String[]{"http", "https"});
        apiSchemeItem.setSelection("https".equals(cfg().apiScheme) ? 1 : 0);
        apiSchemeItem.setOnSelected(idx -> pending.put(Config.KEY_API_SCHEME, idx == 1 ? "https" : "http"));
        root.addView(apiSchemeItem);

        apiKeyItem = new ListItem(this);
        apiKeyItem.fill(getString(R.string.api_key_title), getString(R.string.api_key_sub), null);
        apiKeyItem.setOnClick(() -> textDialog(getString(R.string.api_key_title), cfg().apiKey,
                s -> pending.put(Config.KEY_API_KEY, s)));
        root.addView(apiKeyItem);
        applyApiVisibility(cfg().exposeApi);

        section(root, getString(R.string.syntax_section));
        ListItem help = new ListItem(this);
        help.fill(getString(R.string.expr_help_title), getString(R.string.expr_help_sub), null);
        help.setOnClick(this::showExpressionHelp);
        root.addView(help);

        section(root, getString(R.string.about_section));
        ListItem details = new ListItem(this);
        details.fill(getString(R.string.details_title), getString(R.string.details_sub), null);
        details.setOnClick(this::showAboutDialog);
        root.addView(details);

        ListItem feedback = new ListItem(this);
        feedback.fill(getString(R.string.feedback_title), getString(R.string.feedback_sub), null);
        feedback.setOnClick(this::showFeedbackDialog);
        root.addView(feedback);

        ListItem restart = new ListItem(this);
        restart.fill(getString(R.string.restart_systemui_title),
                getString(R.string.restart_systemui_sub), null);
        restart.setOnClick(this::restartSystemUi);
        root.addView(restart);

        setContentView(scroll);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(getString(R.string.menu_settings));
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
    /* 配置 / 对话框                                                       */
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

    /** 格式化刷新间隔：整秒显示整数，其余显示一位小数。 */
    private static String formatInterval(float sec) {
        if (sec == Math.floor(sec) && !Float.isInfinite(sec)) {
            return String.valueOf((int) sec);
        }
        return String.format(java.util.Locale.US, "%.1f", sec);
    }

    /** 手填更新速度（0.1~300 秒）；输入非法或越界时忽略。 */
    private void speedDialog(ListItem item) {
        textDialog(getString(R.string.speed_dialog), formatInterval(cfg().refreshInterval), s -> {
            try {
                float v = Float.parseFloat(s);
                if (v >= 0.1f && v <= 300f) {
                    pending.put(Config.KEY_REFRESH_INTERVAL, v);
                    item.setValueText(formatInterval(v));
                }
            } catch (NumberFormatException ignored) {
            }
        });
    }

    private void textDialog(String title, String initial, OnText save) {
        final EditText et = new EditText(this);
        et.setText(initial);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(textColor());
        et.setPadding(dp(12), dp(8), dp(12), dp(8));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.save), (d, w) -> save.on(
                        et.getText() == null ? "" : et.getText().toString().trim()))
                .show();
    }

    private void showExpressionHelp() {
        showDetailDialog(getString(R.string.expr_help_dialog_title),
                "在文案中嵌入 ${...} 即可动态求值，系统提示按“更新速度”自动刷新。",
                "需先在“表达式注入”开启后才求值，否则 ${...} 原文直出、不会注入。",
                "",
                "【变量】",
                "${battery}   电池电量（如 87）",
                "${charging}  是否充电（true/false）",
                "${wifi}      当前 WiFi 名称",
                "${bt}        蓝牙设备名",
                "${time}      当前时间（HH:mm）",
                "${date}      当前日期（yyyy-MM-dd）",
                "",
                "【网络函数】",
                "${get(url)}          HTTP GET，返回响应体",
                "${get(url, ttl)}      GET，ttl（毫秒）内不重复请求",
                "${post(url, json)}    HTTP POST，返回响应体",
                "${post(url, json, ttl)}  POST，ttl（毫秒）内不重复请求",
                "",
                "【运算符】",
                "== != > >= < <=   比较，返回 true/false（空串与 null 视为相等）",
                "&& || !           逻辑与 / 或 / 非",
                "+                 数字相加；否则字符串拼接",
                "( )               括号，任意嵌套组合",
                "",
                "【三元（可嵌套）】",
                "${cond ? \"a\" : \"b\"}                   成立取 a，否则 b",
                "${owner == null ? \"无\" : owner}         owner 为空时显示“无”",
                "",
                "【示例】",
                "此设备归 ${battery}% 电量的 我 所有",
                "已通过 ${post(\"http://x/check\", \"{\\\"id\\\":1}\")} 联网",
                "${charging ? \"正在充电\" : \"未充电\"}");
    }

    /** 运行时读版本：不依赖 BuildConfig，直接取 PackageManager。 */
    private String versionString() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void showAboutDialog() {
        showDetailDialog(getString(R.string.details_title),
                "版本 " + versionString(),
                "",
                "本软件遵循 MPL 2.0 开源协议，永不收费。",
                "",
                "本软件不会以任何方式擅自达成以下目的：",
                "· 外连（比如网络、蓝牙）",
                "· 上传任何内容（比如日志）",
                "· 下载任何内容（比如恶意代码）",
                "· 破坏系统",
                "",
                "本软件为 Xposed，依赖 LSPosed 框架运行，在您未授权时我们没有能力修改系统；",
                "另请知悉本软件具有些微危险性。");
    }

    private void showFeedbackDialog() {
        showDetailDialog(getString(R.string.feedback_title),
                "本软件已在 Github 开源。",
                "",
                "更提倡反馈无法扫描到资源的情形，而不是反馈能自动扫描的情形。",
                "反馈系统无法检测到资源时，请附带系统信息和 SystemUI apk。",
                "实在是无人使用的系统请勿反馈，即使发 issue 也不会把资源内置于 apk。");
    }

    /** 弹窗警告后，发送广播让 hook 侧（SystemUI 进程）自杀。 */
    private void restartSystemUi() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.restart_systemui_dialog_title))
                .setMessage(getString(R.string.restart_systemui_dialog_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.restart_systemui_title), (d, w) -> {
                    try {
                        Config cfg = cfg();
                        android.content.Intent i = new android.content.Intent(
                                io.github.fnyoat.tipismine.hook.SystemUiRestarter.ACTION_RESTART_SYSTEMUI);
                        String token = cfg.scanToken;
                        if (token == null || token.isEmpty()) token = cfg.apiKey;
                        if (token != null && !token.isEmpty()) {
                            i.putExtra(io.github.fnyoat.tipismine.hook.SystemUiRestarter.EXTRA_TOKEN, token);
                        }
                        sendBroadcast(i);
                        android.widget.Toast.makeText(this,
                                getString(R.string.restart_systemui_sent),
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(this, getString(R.string.save_failed,
                                        t == null ? "?" : t.getMessage()),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /** 详情对话框：内容按行数组拼成带换行的可选中文本，支持系统长按选择/复制。 */
    private void showDetailDialog(String title, String... lines) {
        final TextView tv = new TextView(this);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        tv.setTextSize(13);
        tv.setLineSpacing(0, 1.4f);
        tv.setTextColor(textColor());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        tv.setText(sb);
        tv.setTextIsSelectable(true);
        tv.setMaxLines(Integer.MAX_VALUE);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(tv)
                .setNegativeButton(getString(R.string.close), null)
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* UI helpers                                                          */
    /* ------------------------------------------------------------------ */

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
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

    private class ListItem extends LinearLayout {
        private TextView valueTv;
        private android.widget.Switch switchView;
        private String[] choices;
        private int selected = -1;
        private OnIdx onSelected;
        private Runnable onClick;

        ListItem(Activity a) {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(9), dp(4), dp(9));
            setClickable(true);
            setBackground(ripple());
            setOnClickListener(v -> {
                if (choices != null) cycle();
                else if (onClick != null) onClick.run();
            });
        }

        void fill(String title, String sub, String[] choices) {
            this.choices = choices;
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(VERTICAL);
            TextView t = new TextView(getContext());
            t.setText(title);
            t.setTextColor(textColor());
            t.setTextSize(16);
            texts.addView(t);
            if (sub != null && !sub.isEmpty()) {
                TextView s = new TextView(getContext());
                s.setText(sub);
                s.setTextColor(subColor());
                s.setTextSize(12);
                texts.addView(s);
            }
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            valueTv = new TextView(getContext());
            valueTv.setTextColor(accent());
            valueTv.setTextSize(14);
            valueTv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            addView(valueTv);
        }

        /** 右侧用 Switch 的条目：标题 +（可选）副标题 + Switch。
         *  点条目本身走 {@link #setOnClick}，点 Switch 走 {@code onChecked}。 */
        void fillSwitch(String title, String sub, boolean checked, OnIdx onChecked) {
            this.choices = null;
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(VERTICAL);
            TextView t = new TextView(getContext());
            t.setText(title);
            t.setTextColor(textColor());
            t.setTextSize(16);
            texts.addView(t);
            if (sub != null && !sub.isEmpty()) {
                TextView s = new TextView(getContext());
                s.setText(sub);
                s.setTextColor(subColor());
                s.setTextSize(12);
                texts.addView(s);
            }
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            switchView = new android.widget.Switch(getContext());
            // 先 setChecked（此时无监听器，不会误触发回调），再注册监听。
            switchView.setChecked(checked);
            switchView.setOnCheckedChangeListener((bv, isChecked) -> {
                if (onChecked != null) onChecked.on(isChecked ? 1 : 0);
            });
            addView(switchView);
        }

        void setSelection(int idx) {
            selected = idx;
            if (choices != null && idx >= 0 && idx < choices.length) {
                valueTv.setText(choices[idx]);
            }
        }

        /** 设置右侧直接显示的文本（无 choices 的条目）。 */
        void setValueText(String s) {
            valueTv.setText(s);
        }

        void setOnSelected(OnIdx cb) { this.onSelected = cb; }
        void setOnClick(Runnable r) { this.onClick = r; }

        void cycle() {
            if (choices == null) return;
            selected = (selected + 1) % choices.length;
            valueTv.setText(choices[selected]);
            if (onSelected != null) onSelected.on(selected);
        }

        private android.graphics.drawable.Drawable ripple() {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                android.graphics.drawable.RippleDrawable rd = new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                        new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), null);
                return rd;
            }
            return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        }
    }
}