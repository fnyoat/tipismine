package io.github.fnyoat.tipismine.hook;

import java.util.HashMap;
import java.util.Map;

/**
 * 决策"给定 SystemUI 的 getString 调用是否/如何改写"的共享逻辑（无框架依赖，可测）。
 *
 * <p>两种 flavor（legacy / modern）的 hook 垫片差异很大，但"命中 key → 按配置得结果"
 * 的判断是一致的，集中在这里避免重复。
 */
public final class PromptRewriter {

    private PromptRewriter() {
    }

    /**
     * 返回实际用于改写的配置：分离开启时，锁屏 key 用锁屏生效配置；
     * 未分离（{@code config.lockscreen == null}）或非锁屏 key 时原样返回（零额外开销）。
     * hook 侧在进入 maybeRewrite / 隐藏意图判定前先取此生效配置。
     */
    public static Config effectiveFor(Config config, String resourceName) {
        if (config == null || config.lockscreen == null) {
            return config;
        }
        if (!OwnershipKeys.isLockScreenKey(resourceName)) {
            return config;
        }
        return config.lockscreen.toEffective(config);
    }

    /**
     * 不带表达式求值、不带活跃集的版本（纯逻辑，用于单元测试）。
     * 使用全量内置名单匹配（等效于所有 key 都活跃）。
     */
    public static String maybeRewrite(Config config, String resPackageName, String resourceName,
                                     Object[] args) {
        return maybeRewrite(config, resPackageName, resourceName, args, null, null, null);
    }

    /**
     * 带表达式求值、不带活跃集的版本（纯逻辑，用于单元测试）。
     */
    public static String maybeRewrite(Config config, String resPackageName, String resourceName,
                                     Object[] args, ExpressionContext ctx) {
        return maybeRewrite(config, resPackageName, resourceName, args, null, ctx, null);
    }

    /** 带模板与表达式求值的版本（hook 侧亦可使用；activeEntries 由活跃名单版本注入）。 */
    public static String maybeRewrite(Config config, String resPackageName, String resourceName,
                                     Object[] args, String template, ExpressionContext ctx) {
        return maybeRewrite(config, resPackageName, resourceName, args, template, ctx, null);
    }

    /**
     * 带活跃集的版本（hook 侧使用，跳过本机不存在的 key）。
     */
    public static String maybeRewrite(Config config, String resPackageName, String resourceName,
                                     Object[] args, String template, ExpressionContext ctx,
                                     String[][] activeEntries) {
        if (config == null || !config.enabled) {
            return null;
        }
        if (!isOwnershipKey(config, resPackageName, resourceName, template, activeEntries)) {
            return null;
        }

        // 整段替换优先。
        String whole = config.wholeTextOrNull();
        if (whole != null) {
            return guardEmpty(apply(whole, config, ctx, args));
        }

        String ownerVs = argAt(args, 0);
        String vpnVs = argAt(args, 1);

        String composed = ComposePrompt.assemble(
                config.ownerMode, config.ownerText, ownerVs,
                config.vpnMode, config.vpnText, vpnVs);

        // 两个槽位都未干预 → 放行系统原值。
        if (composed == null || composed.isEmpty()) {
            // 空文案会被锁屏 KeyguardIndicationController 拒绝并抛
            // "message or icon must be set" 使 SystemUI 崩溃；隐藏意图在此放行系统原值，
            // 绝不把空串注入 SystemUI。详见 hook 层的 Warn 日志。
            return null;
        }
        return guardEmpty(apply(composed, config, ctx, args));
    }

    /** 空文案/纯空白会击穿 Keyguard 的空文案守卫，因此放行系统原值（返回 null=不干预）。
     *  注意：只判空，不 trim——原样保留 SystemUI 需要的缩进/空白；首尾空白的裁剪曾导致
     *  下拉面板（QS disclosure）排布/判定异常而归为"未修改"。 */
    private static String guardEmpty(String out) {
        if (out == null || out.trim().isEmpty()) {
            return null;
        }
        return out;
    }

    /** 输出文本是否含未求值的动态表达式（{@code ${...}}）。
     *  含表达式时 SystemUI 提示需按刷新间隔重算（变量/电量/时间/HTTP 会变）；纯静态文本
     *  一次渲染即可，tick 应完全停摆。 */
    public static boolean isDynamic(String text) {
        if (text == null) return false;
        return text.contains("${");
    }

    /** 配置的<b>源码</b>文本是否含动态表达式（整句 / owner / vpn）。
     *  表达式求值发生在组装之后，最终输出里可能已无 {@code ${...}}（如
     *  {@code ${charging}} 已求值为 true/false），因此是否需按间隔刷新必须看源码而非输出。 */
    public static boolean sourceIsDynamic(Config config) {
        if (config == null) return false;
        return isDynamic(config.wholeText)
                || isDynamic(config.ownerText)
                || isDynamic(config.vpnText);
    }

    /**
     * 对选定的文案执行表达式注入：仅当 {@code config.expressions} 开启时才求值；
     * 关闭时返回原文（其中的 {@code ${...}} 不会注入 SystemUI）。
     */
    private static String apply(String text, Config config, ExpressionContext ctx, Object[] args) {
        if (!config.expressions) {
            return text;
        }
        return evaluate(text, ctx, buildOverrides(args));
    }

    /** 把当前调用实参的 owner / vpn 作为覆写变量提供给表达式求值（缺失时即为 null）。 */
    private static Map<String, String> buildOverrides(Object[] args) {
        Map<String, String> overrides = new HashMap<>(4);
        overrides.put("owner", argAt(args, 0));
        overrides.put("vpn", argAt(args, 1));
        return overrides;
    }

    /** 求值表达式；ctx 为 null 时直接返回原文。 */
    private static String evaluate(String text, ExpressionContext ctx, Map<String, String> overrides) {
        if (ctx == null) return text;
        try {
            return ExpressionEngine.evaluate(text, ctx, overrides);
        } catch (Throwable t) {
            return text;
        }
    }

    private static String argAt(Object[] args, int idx) {
        if (args == null || idx >= args.length) return null;
        Object v = args[idx];
        return v == null ? null : String.valueOf(v);
    }

    /** 是否归属提示 key（内置/自定义名单或内容扫描命中）。hook 侧打 Warn 日志时可复用。 */
    public static boolean isOwnershipKey(Config config, String resPackageName, String resourceName,
                                         String template, String[][] activeEntries) {
        if (config == null || "com.android.systemui".equals(resPackageName) == false) {
            return false;
        }
        boolean builtinHit = activeEntries != null
                ? OwnershipKeys.matchesActive(resourceName, activeEntries)
                : OwnershipKeys.matches(resourceName);
        return builtinHit
                || OwnershipKeys.matchesCustom(resourceName, config.customKeys)
                || (config.contentScan && OwnershipKeys.matchesTemplate(template));
    }

    /**
     * 隐藏全部槽位（组装结果为 null 或空串）时是否即将放行系统原值。
     * 用于 hook 层在"隐藏被解析为空文案 → 放行"的场景打印 Warn，避免把空字符串注入
     * SystemUI 的 KeyguardIndicationController（会抛 message or icon must be set 崩溃）。
     */
    public static boolean mightHideWithEmptyResult(Config config, Object[] args) {
        if (config == null) return false;
        boolean hideRequested = compressMode(config.ownerMode) == SlotMode.HIDE
                || compressMode(config.vpnMode) == SlotMode.HIDE;
        if (!hideRequested) return false;
        if (config.wholeTextOrNull() != null) return false;
        String composed = ComposePrompt.assemble(
                config.ownerMode, config.ownerText, argAt(args, 0),
                config.vpnMode, config.vpnText, argAt(args, 1));
        return composed == null || composed.isEmpty();
    }

    private static int compressMode(int mode) {
        return mode == SlotMode.HIDE ? SlotMode.HIDE : SlotMode.DEFAULT;
    }

    /**
     * 整段替换是否启用且非空（表达式求值可能得到空串，同样需放行）。
     */
    public static boolean wholeRewriteRequested(Config config) {
        return config != null && config.wholeTextOrNull() != null;
    }
}