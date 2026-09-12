package io.github.fnyoat.tipismine.hook;

/**
 * 表达式上下文——提供变量值和 HTTP 执行能力。
 *
 * <p>在 SystemUI（hook 侧）和模块 App（UI 预览侧）各有一套实现，
 * 共享同一份 {@link ExpressionEngine} 纯逻辑求值器。
 */
public interface ExpressionContext {

    /**
     * 返回变量值。
     *
     * <p>内置变量名：
     * <ul>
     *   <li>{@code battery} — 电量 0-100</li>
     *   <li>{@code charging} — "true" / "false"</li>
     *   <li>{@code wifi} — 已连接 WiFi SSID（可能为空）</li>
     *   <li>{@code bt} — 已连接蓝牙设备名（可能为空）</li>
     *   <li>{@code time} — HH:mm</li>
     *   <li>{@code date} — yyyy-MM-dd</li>
     *   <li>{@code owner} — 当前归属名称（由调用方覆写，可为 null，见 {@link ExpressionEngine})</li>
     *   <li>{@code vpn} — 当前 VPN 名称（由调用方覆写，可为 null）</li>
     * </ul>
     *
     * @return 变量值字符串；未知变量返回空串
     */
    String getVariable(String name);

    /** HTTP GET；失败返回空串。 */
    String executeGet(String url);

    /** HTTP POST；失败返回空串。 */
    String executePost(String url, String body);

    /** HTTP GET（带 ttl）；失败返回空串。ttl 单位毫秒，>0 时覆盖默认缓存周期。 */
    String executeGet(String url, long ttl);

    /** HTTP POST（带 ttl）；失败返回空串。ttl 单位毫秒，>0 时覆盖默认缓存周期。 */
    String executePost(String url, String body, long ttl);

    /** 清空所有 HTTP 缓存（表达式提供者调用，如点击“应用”后）。 */
    void clearCache();
}