package io.github.fnyoat.tipismine.hook;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * modern 风味入口（新版 LSPosed 官方 libxposed API）。
 *
 * <p>仅在目标进程（com.android.systemui）被激活加载时实例化，持有 framework 句柄，
 * 可直接调用自身的 {@link XposedInterface} 方法（hook / 远程 prefs / log）。
 * 配置经官方 {@code XRemotePreferences} 读写，跨进程无 SELinux 问题，且变更实时可见。
 *
 * <p>modern API 移除了"资源 hook"子系统，因此直接 hook {@code Resources.getString}
 * 方法本身并改写返回值。
 */
public class TipIsMineXd extends XposedModule {

    public static final String PACKAGE_NAME = "io.github.fnyoat.tipismine";
    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String TAG = "TipIsMine";

    private XposedInterface framework;
    private ModernConfig config;
    private ConfigStore hookStore;
    private PromptAutoRefresh autoRefresh;

    /** 本机存在的内置 key 条目；首次 getString 时惰性构建一次，
     *  之后不存在的 key 根本不参与匹配（节省每帧遍历）。 */
    private volatile String[][] activeEntries;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        this.framework = this;
        this.config = new ModernConfig(framework);
        framework.log(android.util.Log.INFO, TAG, "modern module loaded in " + param.getProcessName());
    }

    /** 当 package 被加载进进程时被调（在系统服务里替换为 onSystemServerStarting）。 */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!PKG_SYSTEMUI.equals(param.getPackageName())) {
            return;
        }
        // 高频 getString 路径不走真实 load()，用 1s TTL 缓存兜住，避免 SystemUI 主线程 Binder/磁盘 I/O。
        hookStore = new CachedConfigStore(config);
        autoRefresh = new PromptAutoRefresh(hookStore);
        SystemUiRestarter.register(AppContextProvider.current(), hookStore);
        hookKeyguardLambda8Guard(param.getDefaultClassLoader());
        hookResources();
        hookTextView();
    }

    /**
     * 根源级隐藏：hook KeyguardIndicationController 用来展示锁屏提示的 Runnable
     * （<code>$$ExternalSyntheticLambda8</code>）。其 {@code run()} 中，当要显示的文本为空时
     * 会抛 {@link IllegalStateException}("message or icon must be set") 使 SystemUI 崩溃。
     *
     * <p>此 hook 在 <b>显示之前</b>拦截：当 lambda 持有的文本为空时直接跳过整个 run（既不
     * updateIndication 也不抛异常），因此 SystemUI 可以不崩溃地显示「无提示」——这才是真正
     * 根源上的完全隐藏，而不是往字符串里塞空格/隐形字符。配合 getString hook 在整段隐藏时
     * 注入空串，即可实现锁屏/QS 无任何残留的完全隐藏。
     */
    private void hookKeyguardLambda8Guard(ClassLoader cl) {
        try {
            Class<?> lambda8 = Class.forName(
                    "com.android.systemui.statusbar.KeyguardIndicationController$$ExternalSyntheticLambda8",
                    false, cl);
            Method run = null;
            for (Method m : lambda8.getDeclaredMethods()) {
                if ("run".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    run = m;
                    break;
                }
            }
            if (run == null) {
                framework.log(android.util.Log.WARN, TAG,
                        "lambda8 run() not found on " + lambda8.getName());
                return;
            }
            framework.hook(run).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new KeyguardLambda8Guard());
            framework.log(android.util.Log.INFO, TAG, "keyguard lambda8 guard active");
            keyguardLambda8GuardReady = true;
        } catch (Throwable t) {
            framework.log(android.util.Log.WARN, TAG, "keyguard lambda8 guard unavailable", t);
        }
    }

    /** 整段隐藏意图时是否已装上 lambda8 guard（guard 失败时不得注入空串，否则会击穿崩溃守卫）。 */
    private volatile boolean keyguardLambda8GuardReady;

    /**
     * <pre>
     * void run() {
     *     KeyguardIndicationController c = this.f$0;
     *     CharSequence text = this.f$1;
     *     if (c.mKeyguardStateController.isShowing()) {
     *         if (TextUtils.isEmpty(text)) throw new IllegalStateException("message or icon must be set");
     *         if (c.mInitialTextColorState == null) throw new IllegalStateException("text color must be set");
     *         c.mRotateTextViewController.updateIndication(1, new KeyguardIndication(text, color), false);
     *     }
     * }
     * </pre>
     * 拦截：f$1 为空 → 直接跳过，不显示、不抛异常。
     */
    private final class KeyguardLambda8Guard implements XposedInterface.Hooker {
        private Field textField;

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object _this = chain.getThisObject();
            if (_this != null) {
                CharSequence text = readText(_this);
                if (text == null || TextUtils.isEmpty(text)) {
                    // 根源隐藏：空文本不进入 KeyguardIndication 流，既不显示也无崩溃。
                    framework.log(android.util.Log.INFO, TAG,
                            "keyguard indication suppressed (empty text)");
                    return null; // 不调用 proceed → 跳过原 run()
                }
            }
            return chain.proceed();
        }

        private CharSequence readText(Object lambda) throws IllegalAccessException {
            if (textField == null) {
                for (Field f : lambda.getClass().getDeclaredFields()) {
                    if (CharSequence.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        textField = f;
                        break;
                    }
                }
            }
            if (textField == null) return null;
            Object v = textField.get(lambda);
            return v instanceof CharSequence ? (CharSequence) v : null;
        }
    }

    private void hookResources() {
        try {
            Class<?> resClass = Class.forName("android.content.res.Resources");
            for (Method m : resClass.getDeclaredMethods()) {
                if (!"getString".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == int.class) {
                    framework.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new GetStringHooker());
                } else if (pts.length == 2 && pts[0] == int.class && pts[1] == Object[].class) {
                    framework.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new GetStringHooker());
                }
            }
        } catch (Throwable t) {
            framework.log(android.util.Log.ERROR, TAG, "hook Resources failed", t);
        }
    }

    /** 捕获正在显示归属提示的 TextView，用于定时自动刷新。 */
    private void hookTextView() {
        try {
            Class<?> tvClass = Class.forName("android.widget.TextView");
            for (Method m : tvClass.getDeclaredMethods()) {
                if (!"setText".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == CharSequence.class) {
                    framework.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new TextViewHooker());
                } else if (pts.length == 2 && pts[0] == CharSequence.class) {
                    // (CharSequence, BufferType)
                    framework.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new TextViewHooker());
                }
            }
        } catch (Throwable t) {
            framework.log(android.util.Log.ERROR, TAG, "hook TextView failed", t);
        }
    }

    /** 每次调用读最新配置（远程 prefs 变更无需重启即生效 → "实时"）。 */
    private final class GetStringHooker implements XposedInterface.Hooker {

        private SystemUiExpressionContext exprCtx;

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object _this = chain.getThisObject();
            if (!(_this instanceof Resources)) {
                return chain.proceed();
            }
            List<Object> args = chain.getArgs();
            if (args == null || args.isEmpty()) {
                return chain.proceed();
            }
            Object idRaw = args.get(0);
            if (!(idRaw instanceof Integer)) {
                return chain.proceed();
            }
            Resources res = (Resources) _this;
            int id = (Integer) idRaw;

            // 首次调用时用 SystemUI 的 Resources 一次性确认本机真实存在的内置 key，
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
                        SystemUiRestarter.register(ctx, hookStore);
                    }
                } catch (Throwable ignored) {
                }
            }

            Object[] fmtArgs = null;
            Object second = args.size() > 1 ? args.get(1) : null;
            if (second instanceof Object[]) {
                fmtArgs = (Object[]) second;
            }

            try {
                String pkg = res.getResourcePackageName(id);
                String name = res.getResourceEntryName(id);
                // 拿原始模板用于内容自适应检测（getText 与 getString 是不同方法，不递归）。
                String template = res.getText(id).toString();
                Config cfg = hookStore.load();
                // 分离开启：锁屏 key 用锁屏生效配置（跟随主页时与主页一致）；未分离/非锁屏 key 原样。
                cfg = PromptRewriter.effectiveFor(cfg, name);
                // 配置签名变化（点击"应用"等）即清空表达式 HTTP 缓存。
                if (exprCtx != null) {
                    exprCtx.checkConfig(cfg);
                }
                String out = PromptRewriter.maybeRewrite(cfg,
                        pkg, name, fmtArgs, template, exprCtx, activeEntries);
                // 诊断：归属类 key 命中打印；另对下拉面板高频重绘也打印（快速定位哪个 key 展示时不被 hook）。
                if (PromptRewriter.isOwnershipKey(cfg, pkg, name, template, activeEntries)) {
                    framework.log(android.util.Log.INFO, TAG,
                            "getString res=" + name + " out=" + (out == null ? "<null>" : "'" + out + "'")
                                    + " ownerMode=" + cfg.ownerMode + " ownerText='" + cfg.ownerText + "'"
                                    + " vpnMode=" + cfg.vpnMode + " vpnText='" + cfg.vpnText + "'"
                                    + " whole='" + cfg.wholeText + "'");
                } else if ("com.android.systemui".equals(pkg)
                        && name.toLowerCase().contains("disclosure")
                        || name.toLowerCase().contains("owner")
                        || name.toLowerCase().contains("vpn")) {
                    framework.log(android.util.Log.INFO, TAG,
                            "near-miss res=" + name + " template=" + template);
                }
                if (out != null) {
                    // 内容命中但不在内置/自定义名单：打日志报告新 key，供用户在调试页手动收录。
                    // 内置名单内（且本机存在）的 key 属“完美命中”，经活跃名单直接应用，无需回报。
                    if (cfg.contentScan
                            && OwnershipKeys.matchesTemplate(template)
                            && !OwnershipKeys.matchesActive(name, activeEntries)
                            && !OwnershipKeys.matchesCustom(name, cfg.customKeys)) {
                        framework.log(android.util.Log.INFO, TAG,
                                "detected disclosure key (not in list): " + name);
                        // 回传模块 App，由调试页自动填入自定义名单（hook 侧远程 prefs 只读无法自写）。
                        try {
                            ScanReporter.report(AppContextProvider.current(), cfg, name);
                        } catch (Throwable t) {
                            framework.log(android.util.Log.WARN, TAG,
                                    "scan report failed", t);
                        }
                    }
                    autoRefresh.registerOutput(out, pkg, name, fmtArgs, template, cfg);
                    return out;
                }
                if (out == null) {
                    // 未干预放行；或整段隐藏（槽位全隐藏 / 整句替换求值为空）——此时注入空串
                    // 而非放行系统原值，配合 keyguard lambda8 guard 实现真正的完全隐藏（锁屏无
                    // 提示、无小钥匙残留、不触发 "message or icon must be set" 崩溃）。
                    if (PromptRewriter.isOwnershipKey(cfg, pkg, name, template, activeEntries)
                            && (PromptRewriter.mightHideWithEmptyResult(cfg, fmtArgs)
                            || PromptRewriter.wholeRewriteRequested(cfg))) {
                        if (!keyguardLambda8GuardReady) {
                            framework.log(android.util.Log.WARN, TAG,
                                    "rewrite yielded empty text but lambda8 guard not ready: "
                                            + "passing through system value to avoid "
                                            + "KeyguardIndicationController crash"
                                            + " [res=" + name + " ownerMode=" + cfg.ownerMode
                                            + " ownerText='" + cfg.ownerText + "' vpnMode=" + cfg.vpnMode
                                            + " vpnText='" + cfg.vpnText + "' whole='"
                                            + cfg.wholeText + "']");
                            return chain.proceed();
                        }
                        framework.log(android.util.Log.INFO, TAG,
                                "consumed empty text (full hide): injected empty for res=" + name);
                        return "";
                    }
                }
            } catch (Throwable t) {
                return chain.proceed();
            }
            return chain.proceed();
        }
    }

    /** 捕获正在显示归属提示的 TextView。 */
    private final class TextViewHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object _this = chain.getThisObject();
            if (_this instanceof TextView) {
                List<Object> args = chain.getArgs();
                if (args != null && !args.isEmpty() && args.get(0) instanceof CharSequence) {
                    autoRefresh.onTextViewSetText((TextView) _this, (CharSequence) args.get(0));
                }
            }
            return chain.proceed();
        }
    }
}