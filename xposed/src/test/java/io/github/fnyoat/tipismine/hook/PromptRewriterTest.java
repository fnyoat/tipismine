package io.github.fnyoat.tipismine.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PromptRewriterTest {

    private static Config cfg(int ownerMode, String ownerText,
                              int vpnMode, String vpnText,
                              boolean whole, String wholeText) {
        return new Config(ownerMode, ownerText, vpnMode, vpnText, whole, wholeText, false, 8080, "", 1, true);
    }

    @Test
    public void nonSystemUiPackage_isIgnored() {
        assertNull(PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, ""),
                "com.android.settings", "vpn_connected", new Object[]{"公司A", "ab"}));
    }

    @Test
    public void nonOwnershipResource_isIgnored() {
        assertNull(PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "公司A", SlotMode.DEFAULT, "", false, ""),
                "com.android.systemui", "some_other_text", new Object[]{"公司A"}));
    }

    @Test
    public void noIntervention_returnsNull() {
        assertNull(PromptRewriter.maybeRewrite(
                cfg(SlotMode.DEFAULT, "", SlotMode.DEFAULT, "", false, ""),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A", "ab"}));
    }

    @Test
    public void defaultMode_customTextAsContent() {
        // 默认模式：是否显示跟随系统（系统有该段才显示），内容仍用自定义文本替换。
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.DEFAULT, "残留主", SlotMode.DEFAULT, "djxn", false, ""),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A", "ab"});
        assertEquals("此设备归 残留主 所有，已通过 djxn 连接到互联网", out);
    }

    @Test
    public void defaultMode_noSystemValue_slotHidden() {
        // 默认模式：系统没有该段值（systemValue 为空）→ 该段整体不显示，不强制出现。
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "公司A", SlotMode.DEFAULT, "djxn", false, ""),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A", null});
        assertEquals("此设备归 公司A 所有", out);
    }

    @Test
    public void ownerShow_vpnDefault_keepsCustomVpnText() {
        // owner 强制显示自定义，vpn 走默认 → vpn 显示与否随系统，内容用自定义文本。
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "公司A", SlotMode.DEFAULT, "djxn", false, ""),
                "com.android.systemui", "vpn_connected", new Object[]{"原主", "系统VPN"});
        assertEquals("此设备归 公司A 所有，已通过 djxn 连接到互联网", out);
    }

    @Test
    public void wholeRewriteTakesPriority() {
        assertEquals("自定义整句",
                PromptRewriter.maybeRewrite(
                        cfg(SlotMode.SHOW, "X", SlotMode.SHOW, "Y", true, "自定义整句"),
                        "com.android.systemui", "vpn_connected", new Object[]{"A", "B"}));
    }

    @Test
    public void wholeRewriteDisabled_ignored() {
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", true, "  "),
                "com.android.systemui", "vpn_connected", new Object[]{"原主", "sys"});
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网", out);
    }

    @Test
    public void argsMayBeShort() {
        // 只有 owner 实参（args[0]，"默认跟随系统"），无 vpn 实参（args[1]=null）
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.DEFAULT, "", SlotMode.SHOW, "ab", false, ""),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A"});
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网", out);
    }

    @Test
    public void qsNamedManagement_noVpnArg_forceShowsVpn() {
        // MIUI 锁屏/控制中心实际高频调用 quick_settings_disclosure_named_management，
        // 模板仅 1 个 owner 占位符（无 VPN 实参）。vpn=SHOW 时应强制合成带 VPN 段的完整文案。
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.DEFAULT, "", SlotMode.SHOW, "djej", false, ""),
                "com.android.systemui", "quick_settings_disclosure_named_management",
                new Object[]{"游戏开发部"});
        assertEquals("此设备归 游戏开发部 所有，已通过 djej 连接到互联网", out);
    }

    @Test
    public void qsNamedManagement_hitsBuiltinKeys() {
        assertTrue(OwnershipKeys.matches("quick_settings_disclosure_named_management"));
        assertTrue(OwnershipKeys.matchesActive("quick_settings_disclosure_named_management",
                OwnershipKeys.builtinKeyEntries()));
    }

    @Test
    public void globalSwitchOff_bypassesAll() {
        // 功能总开关关闭时，即使配置了改写也必须放行系统原值
        Config off = new Config(SlotMode.SHOW, "公司A", SlotMode.HIDE, "", true, "自定义整句",
                false, 8080, "", 1, false);
        assertNull(PromptRewriter.maybeRewrite(
                off, "com.android.systemui", "vpn_connected", new Object[]{"原主", "sys"}));
    }

    @Test
    public void globalSwitchOn_stillRewrites() {
        Config on = new Config(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "",
                false, 8080, "", 1, true);
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网",
                PromptRewriter.maybeRewrite(
                        on, "com.android.systemui", "vpn_connected", new Object[]{"原主", "sys"}));
    }

    @Test
    public void unknownName_butContentScanHits_rewrites() {
        // 资源名不在名单（且没被自成名单收录），但原始文案命中 → 内容自适应命中
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, ""),
                "com.android.systemui", "hybrid_network_indicator_v2", new Object[]{"原主"},
                "This device belongs to %1$s and is connected to the internet through %2$s", null);
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网", out);
    }

    @Test
    public void contentScanOff_unknownName_isIgnored() {
        Config scanOff = new Config(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "",
                false, 8080, "", 1, true, false, "");
        // 名字既不在内置名单也不在自定义名单，且扫描已关 → 忽略。
        assertNull(PromptRewriter.maybeRewrite(
                scanOff, "com.android.systemui", "hybrid_network_indicator_v2", new Object[]{"原主"},
                "This device belongs to %1$s and is connected to the internet through %2$s", null));
    }

    @Test
    public void contentScanOff_butBuiltinHit_stillRewrites() {
        // do_disclosure_with_name 已收录进内置 MIUI/HyperOS 名单 → 扫描关闭也命中（名单优先）。
        Config scanOff = new Config(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "",
                false, 8080, "", 1, true, false, "");
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网",
                PromptRewriter.maybeRewrite(
                        scanOff, "com.android.systemui", "do_disclosure_with_name",
                        new Object[]{"原主", "ab"},
                        "This device belongs to %1$s and is connected to the internet through %2$s", null));
    }

    @Test
    public void customKeysMatch_rewrites() {
        Config withCustom = new Config(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "",
                false, 8080, "", 1, true, false, "custom_owner_indicator_2026");
        // 内容扫描已关，仍凭自定义名单命中（该 key 不在内置名单）
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网",
                PromptRewriter.maybeRewrite(
                        withCustom, "com.android.systemui", "custom_owner_indicator_2026",
                        new Object[]{"原主"},
                        "this key would not content-match", null));
    }

    /* ------------------------------------------------------------------ */
    /* 表达式中 owner / vpn 实参注入                                        */
    /* ------------------------------------------------------------------ */

    private static final ExpressionContext EXPR_CTX = new ExpressionContext() {
        @Override
        public String getVariable(String name) {
            return "";
        }

        @Override
        public String executeGet(String url) {
            return "get:" + url;
        }

        @Override
        public String executePost(String url, String body) {
            return "post:" + url + ":" + body;
        }

        @Override
        public String executeGet(String url, long ttl) {
            return "get:" + url + ":ttl" + ttl;
        }

        @Override
        public String executePost(String url, String body, long ttl) {
            return "post:" + url + ":" + body + ":ttl" + ttl;
        }

        @Override
        public void clearCache() { }
    };

    @Test
    public void wholeRewrite_canReferenceOwnerAndVpnArgs() {
        // 整句替换中 ${owner}/${vpn} 应取当前调用实参；args 缺失时为 null（求值为空串）
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "X", SlotMode.SHOW, "Y", true, "归 ${owner} 所有，经由 ${vpn} 连接"),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A", "公司VPN"},
                "template", EXPR_CTX);
        assertEquals("归 公司A 所有，经由 公司VPN 连接", out);
    }

    @Test
    public void wholeRewrite_ownerMissing_becomesEmptyInOutput() {
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "X", SlotMode.SHOW, "Y", true, "owner=${owner} vpn=${vpn}"),
                "com.android.systemui", "vpn_connected", new Object[]{null, null},
                "template", EXPR_CTX);
        assertEquals("owner= vpn=", out);
    }

    @Test
    public void wholeRewrite_nullComparison_viaArgs() {
        // args 缺 vpn（args[1]=null）：${vpn == null} 为 true，三元取"未连接"
        String out = PromptRewriter.maybeRewrite(
                cfg(SlotMode.SHOW, "X", SlotMode.SHOW, "Y", true,
                        "${vpn == null ? \"未连接\" : vpn}"),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A"},
                "template", EXPR_CTX);
        assertEquals("未连接", out);
    }

    /* ------------------------------------------------------------------ */
    /* 表达式注入开关                                                       */
    /* ------------------------------------------------------------------ */

    private static Config cfgWithExpressions(boolean expressions, String wholeText) {
        return new Config(SlotMode.SHOW, "X", SlotMode.SHOW, "Y",
                true, wholeText,
                false, 8080, "", 1, true, expressions,
                true, "", false, "", "",
                "0.0.0.0", "http");
    }

    @Test
    public void expressionsOff_wholeTextPassedThrough_raw() {
        // 开关关闭：${...} 原文直出，不求值、不注入
        String out = PromptRewriter.maybeRewrite(
                cfgWithExpressions(false, "运行商=${owner} 电量=${battery}"),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A"},
                "template", EXPR_CTX);
        assertEquals("运行商=${owner} 电量=${battery}", out);
    }

    @Test
    public void expressionsOn_stillEvaluates() {
        String out = PromptRewriter.maybeRewrite(
                cfgWithExpressions(true, "运行商=${owner} vpn=${vpn}"),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A", "公司VPN"},
                "template", EXPR_CTX);
        assertEquals("运行商=公司A vpn=公司VPN", out);
    }

    /* ------------------------------------------------------------------ */
    /* 隐藏全部槽位 → 不注入空文案（Keyguard 崩溃修复）                      */
    /* ------------------------------------------------------------------ */

    @Test
    public void hideBothSlots_returnsNull_notEmptyString() {
        // 关键回归：owner / vpn 全部隐藏时，组装结果为空，必须返回 null 放行系统原值，
        // 绝不能把 "" 注入 SystemUI（KeyguardIndicationController 会抛
        // "message or icon must be set" 崩溃）。见 PromptRewriter.maybeRewrite。
        assertNull(PromptRewriter.maybeRewrite(
                cfg(SlotMode.HIDE, "", SlotMode.HIDE, "", false, ""),
                "com.android.systemui", "vpn_connected", new Object[]{"公司A", "ab"}));
    }

    @Test
    public void hideBothSlots_flaggedAsHidePassThrough() {
        Config hid = cfg(SlotMode.HIDE, "", SlotMode.HIDE, "", false, "");
        assertTrue(PromptRewriter.mightHideWithEmptyResult(hid, new Object[]{"公司A", "ab"}));
    }

    @Test
    public void showSlots_notFlaggedAsHidePassThrough() {
        Config show = cfg(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "");
        assertFalse(PromptRewriter.mightHideWithEmptyResult(show, new Object[]{"公司A", "ab"}));
    }

    @Test
    public void wholeRewrite_notFlaggedEvenIfHideRequested() {
        // 整句替换优先级最高：即使某槽位设为隐藏，也不属于"空空放行"。
        Config whole = cfg(SlotMode.HIDE, "", SlotMode.HIDE, "", true, "自定义整句");
        assertFalse(PromptRewriter.mightHideWithEmptyResult(whole, new Object[]{"公司A", "ab"}));
    }

    @Test
    public void wholeRewrite_evaluatesToEmpty_returnsNull() {
        // 整段替换表达式求值结果为空串 → 同样放行（Keyguard 空文案守卫），不能注入 ""。
        assertNull(PromptRewriter.maybeRewrite(
                cfgWithExpressions(true, "${owner == null ? \"\" : owner}"),
                "com.android.systemui", "vpn_connected", new Object[]{null},
                "template", EXPR_CTX));
    }

    @Test
    public void wholeRewrite_evaluatesToWhitespace_returnsNull() {
        assertNull(PromptRewriter.maybeRewrite(
                cfgWithExpressions(true, "   ${owner == null ? \"\" : owner}   "),
                "com.android.systemui", "vpn_connected", new Object[]{null},
                "template", EXPR_CTX));
    }

    @Test
    public void wholeRewrite_flaggedWhenEnabled() {
        Config whole = cfg(SlotMode.HIDE, "", SlotMode.HIDE, "", true, "自定义整句");
        assertTrue(PromptRewriter.wholeRewriteRequested(whole));
        assertFalse(PromptRewriter.wholeRewriteRequested(cfg(SlotMode.DEFAULT, "", SlotMode.DEFAULT, "", false, "")));
    }

    @Test
    public void isDynamic_flagsOnlyExpressionBearingText() {
        assertTrue(PromptRewriter.isDynamic("电量 ${battery}%"));
        assertTrue(PromptRewriter.isDynamic("${time}"));
        assertTrue(PromptRewriter.isDynamic("${get(http://x/y)}"));
        assertFalse(PromptRewriter.isDynamic("此设备归 XX 所有"));
        assertFalse(PromptRewriter.isDynamic("此设备归 $owner 所有"));
        assertFalse(PromptRewriter.isDynamic(null));
        assertFalse(PromptRewriter.isDynamic(""));
    }

    /* ------------------------------------------------------------------ */
    /* 分离修改控制中心与锁屏                                               */
    /* ------------------------------------------------------------------ */

    @Test
    public void lockScreenKey_classification() {
        assertTrue(OwnershipKeys.isLockScreenKey("do_disclosure_with_name"));
        assertTrue(OwnershipKeys.isLockScreenKey("unlock_screen_owner_info"));
        assertTrue(OwnershipKeys.isLockScreenKey("vpn_connected"));
        assertTrue(OwnershipKeys.isLockScreenKey("monitoring_description_named_vpn"));
        assertTrue(OwnershipKeys.isLockScreenKey("connected_to_vpn"));
        // 明确的控制中心/下拉面板披露走主页配置
        assertFalse(OwnershipKeys.isLockScreenKey("quick_settings_disclosure_named_vpn"));
        assertFalse(OwnershipKeys.isLockScreenKey("quick_settings_disclosure_vpns"));
    }

    @Test
    public void effectiveFor_sameInstance_whenNotSeparated() {
        Config base = cfg(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "");
        assertSame(base, PromptRewriter.effectiveFor(base, "do_disclosure_with_name"));
        assertSame(base, PromptRewriter.effectiveFor(base, "quick_settings_disclosure_named_vpn"));
    }

    @Test
    public void effectiveFor_lockKey_usesLockConfig() {
        Config base = cfg(SlotMode.SHOW, "主页方", SlotMode.HIDE, "", false, "");
        base.lockscreen = new LockScreenPrefs(
                false, SlotMode.MODE_SHOW, "锁屏方",  // owner 不跟随主页
                true, SlotMode.MODE_DEFAULT, "",     // vpn 跟随主页
                false, "");
        Config eff = PromptRewriter.effectiveFor(base, "do_disclosure_with_name");
        assertEquals("锁屏方", eff.ownerText);
        assertEquals(SlotMode.HIDE, eff.vpnMode); // 跟随主页 → vpn 沿用主页 hide
    }

    @Test
    public void effectiveFor_qsKey_keepsMainConfig() {
        Config base = cfg(SlotMode.SHOW, "主页方", SlotMode.HIDE, "", false, "");
        base.lockscreen = new LockScreenPrefs(
                false, SlotMode.MODE_SHOW, "锁屏方",
                false, SlotMode.MODE_SHOW, "锁屏VPN",
                false, "");
        // 控制中心 key 仍用主页配置，锁屏配置不参与
        assertSame(base, PromptRewriter.effectiveFor(base, "quick_settings_disclosure_named_vpn"));
    }

    @Test
    public void maybeWrite_lockKey_usesLockScreenEffectiveConfig() {
        Config base = cfg(SlotMode.SHOW, "主页方", SlotMode.SHOW, "主页VPN", false, "");
        base.lockscreen = new LockScreenPrefs(
                false, SlotMode.MODE_SHOW, "锁屏方",
                true, SlotMode.MODE_DEFAULT, "",
                false, "");
        // 锁屏 key → 生效配置 = 锁屏 owner 覆盖 + vpn 跟随主页
        Config eff = PromptRewriter.effectiveFor(base, "do_disclosure_with_name");
        assertEquals("锁屏方", eff.ownerText);
        assertEquals(SlotMode.SHOW, eff.vpnMode);
        assertEquals("主页VPN", eff.vpnText);
        String out = PromptRewriter.maybeRewrite(
                eff, "com.android.systemui", "do_disclosure_with_name", new Object[]{"原主", "原vpn"});
        assertEquals("此设备归 锁屏方 所有，已通过 主页VPN 连接到互联网", out);
        // 控制中心 key → 生效配置 = 主页原样
        assertSame(base, PromptRewriter.effectiveFor(base, "quick_settings_disclosure_named_vpn"));
    }

    @Test
    public void lockscreen_off_hookZeroOverhead() {
        // 未分离：load 保持 lockscreen==null，effectiveFor 原样返回（hook 侧零额外分支）。
        Config base = cfg(SlotMode.SHOW, "公司A", SlotMode.SHOW, "ab", false, "");
        assertEquals(null, base.lockscreen);
    }
}