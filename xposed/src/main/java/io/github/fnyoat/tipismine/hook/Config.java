package io.github.fnyoat.tipismine.hook;

/**
 * 模块配置的内存模型（纯 POJO，无 Android 依赖，便于单元测试）。
 *
 * <p>owner / vpn 各有一个"默认 / 显示 / 不显示"三态 + 自定义文本；另有"整段替换"开关与文案。
 * expose_api 开启后暴露 HTTP API，外部可通过网络直接更新配置。
 */
public final class Config {

    // 存储键名（写进 prefs 的字段名），两个 flavor 共用。
    public static final String PREF_NAME = "tipismine_prefs";

    public static final String KEY_ENABLED = "enabled";               // bool — 功能总开关（关闭则放行系统原值）
    public static final String KEY_EXPRESSIONS = "expressions";       // bool — 表达式注入开关（关闭则 ${...} 不求值，原文直出）
    public static final String KEY_OWNER_MODE = "owner_mode";       // "default" | "show" | "hide"
    public static final String KEY_OWNER_TEXT = "owner_text";       // String（SHOW 时填）
    public static final String KEY_VPN_MODE = "vpn_mode";           // "default" | "show" | "hide"
    public static final String KEY_VPN_TEXT = "vpn_text";           // String（SHOW 时填）
    public static final String KEY_REWRITE_WHOLE = "rewrite_whole"; // bool
    public static final String KEY_WHOLE_TEXT = "whole_text";       // String（整段替换文案）
    public static final String KEY_EXPOSE_API = "expose_api";       // bool — 开启 HTTP API
    public static final String KEY_API_PORT = "api_port";           // int — HTTP API 端口
    public static final String KEY_API_BIND_IP = "api_bind_ip";     // String — HTTP API 绑定 IP（如 0.0.0.0 / 127.0.0.1）
    public static final String KEY_API_SCHEME = "api_scheme";       // String — "http" | "https"
    public static final String KEY_API_KEY = "api_key";             // String — API 认证密钥
    public static final String KEY_REFRESH_INTERVAL = "refresh_interval"; // float(秒，0.1~300) — 提示/预览刷新间隔
    public static final float KEY_DEFAULT_REFRESH_INTERVAL = 1f;
    public static final String KEY_CONTENT_SCAN = "content_scan"; // bool — 内容自适应检测开关（关闭＝只按内置/自定义名单精确匹配）
    public static final String KEY_CUSTOM_KEYS = "custom_keys";   // String(逗号或换行分隔) — 用户自定义命中的资源名名单
    public static final String KEY_DEBUG_PAGE_REMOVED = "debug_page_removed"; // bool — 调试页已移除
    public static final String KEY_SCAN_RESULTS = "scan_results"; // String(换行分隔) — 最近一次扫描捕获到的资源名结果
    public static final String KEY_SCAN_TOKEN = "scan_token";     // String — 广播回传认证令牌（防伪造注入）

    /* "分离修改控制中心与锁屏"：默认关闭（此时锁屏/控制中心共用本配置，hook 零额外开销，
     * 且下述 lock_* 键一律不读取）。开启后锁屏 tip 使用独立的 {owner,vpn,整段} 配置，
     * owner/vpn 各自可"跟随主页"（默认跟随主页=与主页一致）。 */
    public static final String KEY_SEPARATE_LOCKSCREEN = "separate_lockscreen"; // bool — 分离开关
    public static final String KEY_LOCK_OWNER_FOLLOW = "lock_owner_follow";     // bool — 锁屏 owner 跟随主页
    public static final String KEY_LOCK_OWNER_MODE = "lock_owner_mode";         // "default" | "show" | "hide"（不跟随主页时）
    public static final String KEY_LOCK_OWNER_TEXT = "lock_owner_text";         // String
    public static final String KEY_LOCK_VPN_FOLLOW = "lock_vpn_follow";         // bool — 锁屏 vpn 跟随主页
    public static final String KEY_LOCK_VPN_MODE = "lock_vpn_mode";             // "default" | "show" | "hide"
    public static final String KEY_LOCK_VPN_TEXT = "lock_vpn_text";             // String
    public static final String KEY_LOCK_REWRITE_WHOLE = "lock_rewrite_whole";   // bool — 锁屏整段替换
    public static final String KEY_LOCK_WHOLE_TEXT = "lock_whole_text";         // String

    public final int ownerMode;
    public final String ownerText;
    public final int vpnMode;
    public final String vpnText;
    public final boolean rewriteWhole;
    public final String wholeText;
    public final boolean exposeApi;
    public final int apiPort;
    public final String apiBindIp;
    /** "http" | "https"（https 用构建期内置的自签名证书）。 */
    public final String apiScheme;
    public final String apiKey;
    public final float refreshInterval;
    public final boolean enabled;
    /** 内容自适应检测开关：关闭后只按内置 KEYS 与自定义名单精确匹配，不再实时扫描文案。 */
    public final boolean contentScan;
    /** 用户自定义命中的资源名名单（逗号或换行分隔），配合内置 KEYS 一起参与匹配。 */
    public final String customKeys;

    /** 锁屏分离偏好；null = 未分离（控制中心与锁屏共用本配置，hook 零额外开销）。
     *  由各 ConfigStore 在加载时填充（分离开关关闭时保持 null、不读 lock_* 键）。 */
    public volatile LockScreenPrefs lockscreen;
    /** 调试页是否已移除（移除后隐藏入口、固化资源名配置）。 */
    public final boolean debugPageRemoved;
    /** 最近一次扫描捕获到的资源名结果（换行分隔，UI 展示用）。 */
    public final String scanResults;
    /** 广播回传认证令牌：hook 侧把检测到的 key 以显式广播发回模块 App，用此令牌防其它应用伪造。 */
    public final String scanToken;

    /** 表达式注入开关：关闭时 ${...} 不求值、原文直出（见 {@link PromptRewriter}）。默认开。 */
    public boolean expressions = true;

    /**
     * 配置签名：仅包含会影响表达式求值结果的部分（表达式开关 + 各文案字段）。
     * hook 侧每次拿到最新 {@code Config} 时比对，变化即清空 HTTP 缓存
     * （点击"应用"写入新文案 → 签名变化 → get/post 旧缓存作废）。
     */
    public String exprSignature() {
        return (expressions ? "E1" : "E0")
                + ownerText + "\u0001"
                + vpnText + "\u0001"
                + wholeText + "\u0001"
                + customKeys + "\u0001"
                + (lockscreen == null ? "∅" : lockscreen.ownerText + "\u0001"
                    + lockscreen.vpnText + "\u0001" + lockscreen.wholeText);
    }

    public Config(int ownerMode, String ownerText, int vpnMode, String vpnText,
                  boolean rewriteWhole, String wholeText,
                  boolean exposeApi, int apiPort, String apiKey,
                  float refreshInterval, boolean enabled) {
        this(ownerMode, ownerText, vpnMode, vpnText, rewriteWhole, wholeText,
                exposeApi, apiPort, apiKey, refreshInterval, enabled, true, "", false, "", "",
                "0.0.0.0", "http");
    }

    public Config(int ownerMode, String ownerText, int vpnMode, String vpnText,
                  boolean rewriteWhole, String wholeText,
                  boolean exposeApi, int apiPort, String apiKey,
                  float refreshInterval, boolean enabled,
                  boolean contentScan, String customKeys) {
        this(ownerMode, ownerText, vpnMode, vpnText, rewriteWhole, wholeText,
                exposeApi, apiPort, apiKey, refreshInterval, enabled, contentScan, customKeys, false, "", "",
                "0.0.0.0", "http");
    }

    public Config(int ownerMode, String ownerText, int vpnMode, String vpnText,
                  boolean rewriteWhole, String wholeText,
                  boolean exposeApi, int apiPort, String apiKey,
                  float refreshInterval, boolean enabled,
                  boolean contentScan, String customKeys, boolean debugPageRemoved) {
        this(ownerMode, ownerText, vpnMode, vpnText, rewriteWhole, wholeText,
                exposeApi, apiPort, apiKey, refreshInterval, enabled, contentScan, customKeys, debugPageRemoved, "", "",
                "0.0.0.0", "http");
    }

    public Config(int ownerMode, String ownerText, int vpnMode, String vpnText,
                  boolean rewriteWhole, String wholeText,
                  boolean exposeApi, int apiPort, String apiKey,
                  float refreshInterval, boolean enabled,
                  boolean contentScan, String customKeys, boolean debugPageRemoved,
                  String scanToken, String scanResults) {
        this(ownerMode, ownerText, vpnMode, vpnText, rewriteWhole, wholeText,
                exposeApi, apiPort, apiKey, refreshInterval, enabled, contentScan, customKeys, debugPageRemoved, scanToken, scanResults,
                "0.0.0.0", "http");
    }

    public Config(int ownerMode, String ownerText, int vpnMode, String vpnText,
                  boolean rewriteWhole, String wholeText,
                  boolean exposeApi, int apiPort, String apiKey,
                  float refreshInterval, boolean enabled,
                  boolean contentScan, String customKeys, boolean debugPageRemoved,
                  String scanToken, String scanResults,
                  String apiBindIp, String apiScheme) {
        this.ownerMode = ownerMode;
        this.ownerText = ownerText == null ? "" : ownerText;
        this.vpnMode = vpnMode;
        this.vpnText = vpnText == null ? "" : vpnText;
        this.rewriteWhole = rewriteWhole;
        this.wholeText = wholeText == null ? "" : wholeText;
        this.exposeApi = exposeApi;
        this.apiPort = apiPort <= 0 ? 8080 : apiPort;
        this.apiBindIp = apiBindIp == null || apiBindIp.trim().isEmpty() ? "0.0.0.0" : apiBindIp.trim();
        this.apiScheme = apiScheme == null || apiScheme.trim().isEmpty() ? "http" : apiScheme.trim().toLowerCase();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.refreshInterval = refreshInterval <= 0 ? 1f : refreshInterval;
        this.enabled = enabled;
        this.contentScan = contentScan;
        this.customKeys = customKeys == null ? "" : customKeys;
        this.debugPageRemoved = debugPageRemoved;
        this.scanToken = scanToken == null ? "" : scanToken;
        this.scanResults = scanResults == null ? "" : scanResults;
    }

    /** 完整构造器（含表达式注入开关），供各 ConfigStore 从持久化读取后重建。 */
    public Config(int ownerMode, String ownerText, int vpnMode, String vpnText,
                  boolean rewriteWhole, String wholeText,
                  boolean exposeApi, int apiPort, String apiKey,
                  float refreshInterval, boolean enabled, boolean expressions,
                  boolean contentScan, String customKeys, boolean debugPageRemoved,
                  String scanToken, String scanResults,
                  String apiBindIp, String apiScheme) {
        this(ownerMode, ownerText, vpnMode, vpnText, rewriteWhole, wholeText,
                exposeApi, apiPort, apiKey, refreshInterval, enabled,
                contentScan, customKeys, debugPageRemoved, scanToken, scanResults,
                apiBindIp, apiScheme);
        this.expressions = expressions;
    }

    /** 整段替换是否启用且非空。 */
    public boolean wholeEnabled() {
        return rewriteWhole && !wholeText.trim().isEmpty();
    }

    /** 整段替换优先级最高，命中时无需再拼装。 */
    public String wholeTextOrNull() {
        return wholeEnabled() ? wholeText : null;
    }
}