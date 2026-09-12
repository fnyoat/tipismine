package io.github.fnyoat.tipismine.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ComposePromptTest {

    @Test
    public void bothDefault_returnsNull() {
        assertNull(ComposePrompt.assemble(
                SlotMode.DEFAULT, "", "某公司",
                SlotMode.DEFAULT, "", "ab"));
    }

    @Test
    public void showOwner_withSystemVpn() {
        assertEquals("此设备归 公司A 所有，已通过 系统VPN 连接到互联网",
                ComposePrompt.assemble(SlotMode.SHOW, "公司A", "原主",
                        SlotMode.DEFAULT, "", "系统VPN"));
    }

    @Test
    public void showVpn_withSystemOwner() {
        assertEquals("此设备归 原主 所有，已通过 ab 连接到互联网",
                ComposePrompt.assemble(SlotMode.DEFAULT, "", "原主",
                        SlotMode.SHOW, "ab", "sysvpn"));
    }

    @Test
    public void hideOwner_leavesOnlyVpn() {
        assertEquals("此设备已通过 ab 连接到互联网",
                ComposePrompt.assemble(SlotMode.HIDE, "", "原主",
                        SlotMode.SHOW, "ab", "sysvpn"));
    }

    @Test
    public void hideVpn_leavesOnlyOwner() {
        assertEquals("此设备归 公司A 所有",
                ComposePrompt.assemble(SlotMode.SHOW, "公司A", "原主",
                        SlotMode.HIDE, "", "sysvpn"));
    }

    @Test
    public void bothShow() {
        assertEquals("此设备归 公司A 所有，已通过 ab 连接到互联网",
                ComposePrompt.assemble(SlotMode.SHOW, "公司A", "原主",
                        SlotMode.SHOW, "ab", "sysvpn"));
    }

    @Test
    public void bothHide_returnsEmpty() {
        // 两槽都“隐藏”→ 明确干预，返回空文案（隐藏整句），而非放行系统原值
        assertEquals("", ComposePrompt.assemble(SlotMode.HIDE, "", "原主",
                SlotMode.HIDE, "", "sysvpn"));
    }

    @Test
    public void showWithBlankText_fallsThroughToSystem() {
        // SHOW 但没填文本 → 该槽位不构成干预，等价 DEFAULT → 整条提示放行系统原值
        assertNull(ComposePrompt.assemble(SlotMode.DEFAULT, "", "原主",
                SlotMode.SHOW, "   ", "系统VPN"));
        assertNull(ComposePrompt.assemble(SlotMode.SHOW, "", "原主",
                SlotMode.DEFAULT, "", "系统VPN"));
    }

    @Test
    public void hideVpnWithNoOwner_returnsEmpty() {
        // owner 默认且无系统值 + vpn 隐藏 → 整句被隐藏，返回空文案
        assertEquals("", ComposePrompt.assemble(SlotMode.DEFAULT, "", "",
                SlotMode.HIDE, "", "sysvpn"));
    }

    @Test
    public void intervenePredicate() {
        assertTrue(ComposePrompt.intervenes(SlotMode.SHOW, "AB"));
        assertTrue(ComposePrompt.intervenes(SlotMode.HIDE, ""));
        assertFalse(ComposePrompt.intervenes(SlotMode.SHOW, "  "));
        assertFalse(ComposePrompt.intervenes(SlotMode.DEFAULT, ""));
        // 默认 + 填了自定义文本 → 只改内容，也算干预
        assertTrue(ComposePrompt.intervenes(SlotMode.DEFAULT, "我的设备"));
    }

    @Test
    public void defaultWithOwnerText_keepsSystemVisibilityAndReplacesContent() {
        // 默认 + 填了 owner 文本，vpn 默认无文本 → owner 内容被替换，vpn 跟随系统
        assertEquals("此设备归 我的公司 所有，已通过 系统VPN 连接到互联网",
                ComposePrompt.assemble(SlotMode.DEFAULT, "我的公司", "原主",
                        SlotMode.DEFAULT, "", "系统VPN"));
    }

    @Test
    public void defaultWithVpnText_onlyContentReplaced() {
        assertEquals("此设备归 原主 所有，已通过 我的专线 连接到互联网",
                ComposePrompt.assemble(SlotMode.DEFAULT, "", "原主",
                        SlotMode.DEFAULT, "我的专线", "sysvpn"));
    }

    @Test
    public void defaultWithText_butNoSystemContent_fallsThrough() {
        // 默认填了文本、但系统两侧都无内容可显示 → 放行系统原值（不强行隐藏整句）
        assertNull(ComposePrompt.assemble(SlotMode.DEFAULT, "我的设备", "",
                SlotMode.DEFAULT, "", ""));
    }

    @Test
    public void defaultTexts_dontForceShowWhenSystemHides() {
        // 默认 + 有文本 ≠ 强制显示：系统不显示 vpn 段时，vpn 段仍不出现
        assertEquals("此设备归 我的公司 所有",
                ComposePrompt.assemble(SlotMode.DEFAULT, "我的公司", "原主",
                        SlotMode.DEFAULT, "我的专线", ""));
    }

    @Test
    public void hideOwnerWithText_leavesVpnFollowingSystem() {
        // 即便填了文本，HIDE 仍是隐藏该槽位；另一槽跟随系统
        assertEquals("此设备已通过 系统VPN 连接到互联网",
                ComposePrompt.assemble(SlotMode.HIDE, "我的公司", "原主",
                        SlotMode.DEFAULT, "", "系统VPN"));
    }

    @Test
    public void classFileRefersSlotVisibility() {
        // 槽位存在性
        ComposePrompt.Slot s = new ComposePrompt.Slot(true, "x");
        assertTrue(s.present);
    }
}