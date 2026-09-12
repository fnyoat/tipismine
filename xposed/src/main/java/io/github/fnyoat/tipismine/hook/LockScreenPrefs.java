package io.github.fnyoat.tipismine.hook;

/**
 * 锁屏独立偏好（"分离修改控制中心与锁屏"开启后的锁屏侧配置），纯逻辑、无 Android 依赖。
 *
 * <p>owner / vpn 各自可有"跟随主页"（默认）：跟随时不使用下面的模式 / 文案，
 * 完全沿用 {@link Config} 主页对应槽位。整段替换在锁屏侧独立存储。
 *
 * <p>为空/未分离时，实体存放在 {@link Config#lockscreen} 为 null（分离开关关闭时
 * hook 侧完全不读 lock_* 键、不进入任何锁屏分支，保持零额外开销）。
 */
public final class LockScreenPrefs {

    public final boolean ownerFollow;
    public final String ownerMode; // "default" | "show" | "hide"（仅不跟随主页时使用）
    public final String ownerText;
    public final boolean vpnFollow;
    public final String vpnMode;   // "default" | "show" | "hide"（仅不跟随主页时使用）
    public final String vpnText;
    public final boolean rewriteWhole;
    public final String wholeText;

    public LockScreenPrefs(boolean ownerFollow, String ownerMode, String ownerText,
                           boolean vpnFollow, String vpnMode, String vpnText,
                           boolean rewriteWhole, String wholeText) {
        this.ownerFollow = ownerFollow;
        this.ownerMode = ownerMode == null ? SlotMode.MODE_DEFAULT : ownerMode;
        this.ownerText = ownerText == null ? "" : ownerText;
        this.vpnFollow = vpnFollow;
        this.vpnMode = vpnMode == null ? SlotMode.MODE_DEFAULT : vpnMode;
        this.vpnText = vpnText == null ? "" : vpnText;
        this.rewriteWhole = rewriteWhole;
        this.wholeText = wholeText == null ? "" : wholeText;
    }

    /** 锁屏整段替换是否启用且非空模板。 */
    public boolean wholeEnabled() {
        return rewriteWhole && !wholeText.trim().isEmpty();
    }

    public int ownerModeInt() {
        return SlotMode.from(ownerMode);
    }

    public int vpnModeInt() {
        return SlotMode.from(vpnMode);
    }

    /**
     * 把锁屏偏好与主页配置合并为锁屏生效配置：
     * <ul>
     *   <li>owner / vpn 各自跟随主页 → 用主页该槽位；否则用锁屏自己的模式与文案；</li>
     *   <li>锁屏整段替换启用 → 锁屏整段优先级最高；</li>
     *   <li>两个槽位都跟随主页且锁屏未开整段时，锁定屏与主页完全一致（直接复用主页对象）。</li>
     * </ul>
     */
    public Config toEffective(Config main) {
        if (main == null) return null;
        if (ownerFollow && vpnFollow && !wholeEnabled()) {
            return main;
        }
        int om = ownerFollow ? main.ownerMode : ownerModeInt();
        String ot = ownerFollow ? main.ownerText : ownerText;
        int vm = vpnFollow ? main.vpnMode : vpnModeInt();
        String vt = vpnFollow ? main.vpnText : vpnText;
        boolean whole = wholeEnabled();
        String wt = whole ? wholeText : "";
        return new Config(om, ot, vm, vt, whole, wt,
                main.exposeApi, main.apiPort, main.apiKey, main.refreshInterval, main.enabled,
                main.expressions, main.contentScan, main.customKeys, main.debugPageRemoved,
                main.scanToken, main.scanResults, main.apiBindIp, main.apiScheme);
    }
}