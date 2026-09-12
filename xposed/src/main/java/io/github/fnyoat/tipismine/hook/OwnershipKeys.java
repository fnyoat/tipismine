package io.github.fnyoat.tipismine.hook;

/**
 * 与“归属 + 已通过 VPN 连接到互联网”提示相关的 SystemUI 资源 key 名匹配。
 * 纯逻辑、无 Android 依赖，便于单元测试。
 */
public final class OwnershipKeys {

    /**
 * 不同安卓版本 / 厂商 ROM 上该提示的资源名（可能带前后缀，因此用包含匹配）。
 * 除 AOSP 传统命名外，收录 MIUI/HyperOS 的实际资源 key（unlock_screen_owner_info、
 * do_disclosure_with_name、monitoring_description_*、quick_settings_disclosure_*）。
 * 每条标注其来源系统，便于维护调试页展示。
 */
static final String[][] KEYS = {
        // —— AOSP 原生 ——
        {"vpn_connected", "AOSP"},
        {"connected_to_vpn", "AOSP"},
        {"device_owner_vpn_connected", "AOSP"},
        {"managed_device_vpn_connected", "AOSP"},
        {"work_profile_vpn_connected", "AOSP"},
        {"personal_vpn_connected", "AOSP"},
        {"owner_connected_to_vpn", "AOSP"},
        {"monitoring_description_named_vpn", "AOSP"},
        {"monitoring_description_two_named_vpns", "AOSP"},
        {"monitoring_description_managed_device_named_vpn", "AOSP"},
        {"monitoring_description_managed_profile_named_vpn", "AOSP"},
        {"monitoring_description_personal_profile_named_vpn", "AOSP"},
        {"quick_settings_disclosure_named_management", "AOSP"},
        {"quick_settings_disclosure_named_vpn", "AOSP"},
        {"quick_settings_disclosure_vpns", "AOSP"},
        {"quick_settings_disclosure_named_management_named_vpn", "AOSP"},
        {"quick_settings_disclosure_named_management_vpns", "AOSP"},
        {"quick_settings_disclosure_management_named_vpn", "AOSP"},
        {"quick_settings_disclosure_management_vpns", "AOSP"},
        {"quick_settings_disclosure_personal_profile_named_vpn", "AOSP"},
        {"quick_settings_disclosure_managed_profile_named_vpn", "AOSP"},
        // —— MIUI / HyperOS ——
        {"unlock_screen_owner_info", "MIUI/HyperOS"},
        {"do_disclosure_with_name", "MIUI/HyperOS"}
};

    /** 归属语义特征词（英文/中文）。命中其一即说明文案在陈述"设备归某人/某组织所有"。 */
    static final String[] OWNERSHIP_MARKERS = {
            "belongs to", "owned by", "device is managed", "this device is owned",
            "本设备属于", "设备属于", "设备归", "归属组织", "由以下机构管理",
            "controlled by", "的组织"
    };

    /** 网络/VPN 连接语义特征词（英文/中文）。命中其一即说明文案在陈述"通过网络/VPN 连接"。 */
    static final String[] CONNECTION_MARKERS = {
            "connected to the internet", "internet through", "vpn", "vpn ", "via vpn",
            "连接到互联网", "连接互联网", "通过", "网络连接", "接入网络", "vpn 连接", "vpn连接"
    };

    private OwnershipKeys() {
    }

    /** 内置目标资源列表（只读，UI 调试页展示用）。返回 {name, system} 二维数组。 */
    public static String[][] builtinKeyEntries() {
        return KEYS.clone();
    }

    /**
     * 构建本机实际存在的内置 key 条目（{name, system} 二维数组，仅含存在的项）。
     * 用 {@code Resources.getIdentifier} 逐个检查内置 key 是否在目标进程的资源表里存在。
     * 不存在的 key 永远不会被 getString 命中，直接从匹配数据里剔除——之后遍历根本不触达它们。
     *
     * @param targetRes 目标进程的 Resources（如 SystemUI 的）
     * @return 仅含本机存在的 {name, system} 条目数组（可能为空数组）
     */
    public static String[][] buildActiveEntries(android.content.res.Resources targetRes) {
        java.util.ArrayList<String[]> active = new java.util.ArrayList<>(KEYS.length);
        if (targetRes == null) return new String[0][];
        for (String[] entry : KEYS) {
            try {
                int id = targetRes.getIdentifier(entry[0], "string", "com.android.systemui");
                if (id != 0) {
                    active.add(entry);
                }
            } catch (Throwable ignored) {
            }
        }
        return active.toArray(new String[0][]);
    }

    /** 判断一个 key 名称是否存在于给定的活跃 key 名称集合中（调试页展示用）。 */
    public static boolean isActive(String keyName, java.util.Set<String> activeKeyNames) {
        return activeKeyNames != null && activeKeyNames.contains(keyName);
    }

    /** 把活跃条目数组（{name, system}）转成 key 名称集合。 */
    public static java.util.Set<String> entryNamesToSet(String[][] entries) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (entries != null) {
            for (String[] e : entries) {
                if (e != null && e.length > 0) names.add(e[0]);
            }
        }
        return names;
    }

    /** 判断一个 SystemUI 字符串资源名是否命中归属提示（全量内置名单，用于单元测试）。 */
    public static boolean matches(String resourceName) {
        if (resourceName == null) return false;
        for (String[] k : KEYS) {
            if (resourceName.contains(k[0])) return true;
        }
        return false;
    }

    /**
     * 判断一个资源名是否命中归属提示（仅遍历本机存在的活跃条目）。
     * 不存在的 key 已在活跃条目之外，根本不会进入 contains 判断。
     */
    public static boolean matchesActive(String resourceName, String[][] activeEntries) {
        if (resourceName == null || activeEntries == null) return false;
        for (String[] k : activeEntries) {
            if (resourceName.contains(k[0])) return true;
        }
        return false;
    }

    /**
     * 判断一个资源名是否在锁屏（Keyguard）上展示：SystemUI getString 的归属提示
     * 分两处——锁屏 KeyguardIndication 与下拉面板（QuickSettings/控制中心）。
     * 分离修改控制中心与锁屏时，控制中心 key 归主页配置、锁屏 key 归锁屏配置。
     */
    public static boolean isLockScreenKey(String resourceName) {
        if (resourceName == null) return false;
        // 明确的控制中心/下拉面板披露 key 走"主页"配置；其余（monitoring_description_*、
        // do_disclosure_with_name、vpn_connected、connected_to_vpn 等）在锁屏展示。
        return resourceName.contains("quick_settings_disclosure") == false;
    }

    /** 判断资源名是否命中用户自定义名单（带分隔符拆分的原始文本，逗号或换行分隔）。 */
    public static boolean matchesCustom(String resourceName, String customKeys) {
        if (resourceName == null || customKeys == null) return false;
        String n = resourceName.toLowerCase();
        for (String part : splitCustomKeys(customKeys)) {
            if (n.contains(part)) return true;
        }
        return false;
    }

    /** 拆分自定义名单；空串 / 全空白 / null 返回空数组。大小写不敏感（与模板检测一致）。 */
    public static String[] splitCustomKeys(String customKeys) {
        if (customKeys == null || customKeys.trim().isEmpty()) return new String[0];
        String[] parts = customKeys.split("[,，\n\r]");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t.toLowerCase());
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 内容自适应检测：不看资源名，直接判定原始字符串模板是否是"归属 + 网络/VPN 连接"提示。
     * 任何厂商/系统只要改了资源名（如 MIUI 的 do_disclosure_with_name）都能识别，
     * 不再依赖手工维护的 key 名单。
     *
     * @param template hook 拿到的原始字符串模板（未格式化，可能含 %1$s / %2$s）
     */
    public static boolean matchesTemplate(String template) {
        if (template == null) return false;
        String t = template.toLowerCase();
        return containsAny(t, OWNERSHIP_MARKERS)
                && containsAny(t, CONNECTION_MARKERS);
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String m : markers) {
            if (text.contains(m)) return true;
        }
        return false;
    }
}
