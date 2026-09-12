package io.github.fnyoat.tipismine.hook;

/**
 * 组装“此设备归 … 所有，已通过 … 连接到互联网”的最终文案（纯逻辑，无 Android 依赖）。
 *
 * <p>在 SystemUI 里，这条提示是由 {@code Resources.getString(id, args)} 按分支模板 + 实参
 * 拼出来的。本类把这些分支统一收口：给定 owner / vpn 两个槽位的三态与文本，以及系统原本要
 * 填的名称，决定最终文案。若两个槽位都是“默认”，返回 {@code null} 表示不进任何干预。
 *
 * <p>文案模板（尽量贴近 AOSP 中文习惯，跨版本统一）：
 * <ul>
 *   <li>owner + vpn：{@code 此设备归 <owner> 所有，已通过 <vpn> 连接到互联网}</li>
 *   <li>仅 owner：{@code 此设备归 <owner> 所有}</li>
 *   <li>仅 vpn：{@code 此设备已通过 <vpn> 连接到互联网}</li>
 * </ul>
 */
public final class ComposePrompt {

    private ComposePrompt() {
    }

    /** 槽位结果：某段是否出现、以及该段文本。 */
    public static final class Slot {
        public final boolean present;
        public final String text;

        Slot(boolean present, String text) {
            this.present = present;
            this.text = text;
        }
    }

    /** 判断是否对某槽位做干预。干预 = 最终文案与该段的系统原值不同。
     * <ul>
     *   <li>SHOW：强制显示该段（需提供自定义文本）；</li>
     *   <li>HIDE：强制去掉该段；</li>
     *   <li>DEFAULT：显示与否完全跟随系统，但若填有自定义文本，则用自定义文本替换内容。</li>
     * </ul> */
    public static boolean intervenes(int mode, String customText) {
        if (mode == SlotMode.SHOW) {
            return customText != null && !customText.trim().isEmpty();
        }
        if (mode == SlotMode.HIDE) {
            return true;
        }
        // 默认：仅当用户提供了自定义文本才算干预（只改内容、不改显示状态）。
        return customText != null && !customText.trim().isEmpty();
    }

    /**
     * @param ownerMode  owner 槽位三态
     * @param ownerText  owner 设为 SHOW 时填的自定义文本
     * @param ownerVs    SystemUI 原本要填的 owner 名（可能为空）
     * @param vpnMode    vpn 槽位三态
     * @param vpnText    vpn 设为 SHOW 时填的自定义文本
     * @param vpnVs      SystemUI 原本要填的 vpn 名（可能为空）
     * @return 组装好的文案；若两个槽位都未干预则返回 {@code null}
     */
    public static String assemble(int ownerMode, String ownerText, String ownerVs,
                                  int vpnMode, String vpnText, String vpnVs) {
        Slot owner = resolve("owner", ownerMode, ownerText, ownerVs);
        Slot vpn = resolve("vpn", vpnMode, vpnText, vpnVs);

        boolean ownerIntervened = intervenes(ownerMode, ownerText);
        boolean vpnIntervened = intervenes(vpnMode, vpnText);
        if (!ownerIntervened && !vpnIntervened) {
            return null;
        }

        if (owner.present && vpn.present) {
            return "此设备归 " + owner.text + " 所有，已通过 " + vpn.text + " 连接到互联网";
        }
        if (owner.present) {
            return "此设备归 " + owner.text + " 所有";
        }
        if (vpn.present) {
            return "此设备已通过 " + vpn.text + " 连接到互联网";
        }

        // 明确隐藏过某个槽位 → 返回空文案（隐藏整句），而不是放行系统原值。
        if (ownerMode == SlotMode.HIDE || vpnMode == SlotMode.HIDE) {
            return "";
        }
        // 其余情况（如“默认”模式下填了自定义文本，但系统两侧本就没有内容可输出）：
        // 无任何内容可替换，放行系统处理。
        return null;
    }

    /** 决定某槽位最终是否出现及文本。 */
    private static Slot resolve(String kind, int mode, String customText, String systemValue) {
        if (mode == SlotMode.HIDE) {
            return new Slot(false, null);
        }
        if (mode == SlotMode.SHOW) {
            if (customText != null && !customText.trim().isEmpty()) {
                return new Slot(true, customText.trim());
            }
            return new Slot(false, null);
        }
        // 默认：是否显示完全跟随系统（systemValue 有值才显示），
        // 但内容用自定义文本替换（残留文本在默认模式下也是有效内容）。
        if (systemValue != null && !systemValue.trim().isEmpty()) {
            String text = systemValue.trim();
            if (customText != null && !customText.trim().isEmpty()) {
                text = customText.trim();
            }
            return new Slot(true, text);
        }
        return new Slot(false, null);
    }
}