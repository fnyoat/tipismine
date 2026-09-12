package io.github.fnyoat.tipismine.hook;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * legacy 风味的 SystemUI hook：改写 {@code Resources.getString} 字符串结果，
 * 并按刷新间隔自动刷新归属提示文案。不触碰 hasDeviceOwner / hasProfileOwner 等底层判定。
 */
final class SystemUiHooks {

    private static final String TAG = "TipIsMine";

    private final ConfigStore store;
    private final ClassLoader classLoader;
    private SystemUiExpressionContext exprCtx;
    private PromptAutoRefresh autoRefresh;

    /** 本机存在的内置 key 条目；首帧惰性构建，之后不存在的 key 根本不参与匹配。 */
    private volatile String[][] activeEntries;

    /** 整段隐藏意图时是否已装上 lambda8 guard（guard 失败时不得注入空串，否则会击穿崩溃守卫）。 */
    private volatile boolean keyguardLambda8GuardReady;

    SystemUiHooks(ClassLoader cl, ConfigStore store) {
        // 高频 getString 路径不走真实 load()，用 1s TTL 缓存兜住，避免 SystemUI 主线程 I/O。
        this.classLoader = cl;
        this.store = new CachedConfigStore(store);
        this.autoRefresh = new PromptAutoRefresh(this.store);
    }

    void install() {
        SystemUiRestarter.register(AppContextProvider.current(), store);
        hookKeyguardLambda8Guard();
        hookResources();
        hookTextView();
    }

    /**
     * 根源级隐藏：hook KeyguardIndicationController 用来展示锁屏提示的 Runnable
     * （<code>$$ExternalSyntheticLambda8</code>）的 {@code run()}，在其抛出
     * "message or icon must be set" 之前拦截空文本，直接跳过显示——不显示也不崩溃。
     */
    private void hookKeyguardLambda8Guard() {
        try {
            Class<?> lambda8 = Class.forName(
                    "com.android.systemui.statusbar.KeyguardIndicationController$$ExternalSyntheticLambda8",
                    false, classLoader);
            XposedHelpers.findAndHookMethod(lambda8, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject == null) return;
                    CharSequence text = readText(param.thisObject);
                    if (text == null || TextUtils.isEmpty(text)) {
                        // 根源隐藏：空文本不进入 KeyguardIndication 流，既不显示也不抛异常。
                        Log.i(TAG, "keyguard indication suppressed (empty text)");
                        param.setResult(null);
                    }
                }

                private CharSequence readText(Object lambda) throws Throwable {
                    Field found = null;
                    for (Field f : lambda.getClass().getDeclaredFields()) {
                        if (CharSequence.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            found = f;
                            break;
                        }
                    }
                    if (found == null) return null;
                    Object v = found.get(lambda);
                    return v instanceof CharSequence ? (CharSequence) v : null;
                }
            });
            keyguardLambda8GuardReady = true;
            Log.i(TAG, "keyguard lambda8 guard active");
        } catch (Throwable t) {
            Log.w(TAG, "keyguard lambda8 guard unavailable", t);
        }
    }

    private void hookResources() {
        XC_MethodHook strip = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Resources res = (Resources) param.thisObject;
                    if (res == null) return;

                    // 首次调用时用 SystemUI 的 Resources 一次性确认本机真实存在的 key，
                    // 之后匹配只遍历存活名单（不存在的 key 根本不进遍历）。
                    if (activeEntries == null) {
                        activeEntries = OwnershipKeys.buildActiveEntries(res);
                        autoRefresh.setActiveEntries(activeEntries);
                    }

                    /* 延迟初始化表达式上下文（首次调用时从全局 Application 取 Context） */
                    if (exprCtx == null) {
                        try {
                            Context ctx = AppContextProvider.current();
                            if (ctx != null) {
                                exprCtx = new SystemUiExpressionContext(ctx);
                                autoRefresh.setExpressionContext(exprCtx);
                                SystemUiRestarter.register(ctx, store);
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    int id = (int) param.args[0];
                    String pkg = res.getResourcePackageName(id);
                    String name = res.getResourceEntryName(id);
                    Object[] args = param.args.length > 1 && param.args[1] instanceof Object[]
                            ? (Object[]) param.args[1] : null;
                    // 拿原始模板用于内容自适应检测（getText 与 getString 是不同方法，不递归）。
                    String template = res.getText(id).toString();
                    Config cfg = store.load();
                    // 分离开启：锁屏 key 用锁屏生效配置（跟随主页时与主页一致）；未分离/非锁屏 key 原样。
                    cfg = PromptRewriter.effectiveFor(cfg, name);
                    if (exprCtx != null) {
                        exprCtx.checkConfig(cfg);
                    }
                    String out = PromptRewriter.maybeRewrite(cfg, pkg, name, args, template, exprCtx, activeEntries);
                    if (out != null) {
                        // 内容命中但不在内置/自定义名单：打日志报告新 key，供用户在调试页手动收录。
                        if (cfg.contentScan
                                && OwnershipKeys.matchesTemplate(template)
                                && !OwnershipKeys.matchesActive(name, activeEntries)
                                && !OwnershipKeys.matchesCustom(name, cfg.customKeys)) {
                            Log.i(TAG, "detected disclosure key (not in list): " + name);
                            // 回传模块 App 自动填入自定义名单。
                            ScanReporter.report(AppContextProvider.current(), cfg, name);
                        }
                        autoRefresh.registerOutput(out, pkg, name, args, template, cfg);
                        param.setResult(out);
                    } else if (PromptRewriter.isOwnershipKey(cfg, pkg, name, template, activeEntries)
                            && (PromptRewriter.mightHideWithEmptyResult(cfg, args)
                            || PromptRewriter.wholeRewriteRequested(cfg))) {
                        // 未干预放行；或整段隐藏（槽位全隐藏 / 整句替换求值为空）——注入空串
                        // 而非放行系统原值，配合 keyguard lambda8 guard 实现真正的完全隐藏。
                        if (!keyguardLambda8GuardReady) {
                            Log.w(TAG, "rewrite yielded empty text but lambda8 guard not ready: "
                                    + "passing through system value to avoid "
                                    + "KeyguardIndicationController crash");
                        } else {
                            Log.i(TAG, "consumed empty text (full hide): injected empty for res=" + name);
                            param.setResult("");
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "rewrite failed", t);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getString", int.class, strip);
        } catch (Throwable t) {
            Log.e(TAG, "hook getString(int) failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Resources.class, "getString", int.class, Object[].class, strip);
        } catch (Throwable t) {
            Log.e(TAG, "hook getString(int,Object[]) failed", t);
        }
    }

    /** 捕获正在显示归属提示的 TextView，用于定时自动刷新。 */
    private void hookTextView() {
        XC_MethodHook grab = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    TextView tv = (TextView) param.thisObject;
                    if (tv == null || param.args == null || param.args.length == 0) return;
                    Object text = param.args[0];
                    if (text instanceof CharSequence) {
                        autoRefresh.onTextViewSetText(tv, (CharSequence) text);
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, grab);
        } catch (Throwable t) {
            Log.e(TAG, "hook setText(CharSequence) failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    TextView.class, "setText", CharSequence.class, TextView.BufferType.class, grab);
        } catch (Throwable t) {
            Log.e(TAG, "hook setText(CharSequence,BufferType) failed", t);
        }
    }
}