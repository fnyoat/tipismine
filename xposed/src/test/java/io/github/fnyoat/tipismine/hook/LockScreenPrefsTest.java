package io.github.fnyoat.tipismine.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LockScreenPrefsTest {

    private static Config main() {
        return new Config(SlotMode.SHOW, "主页所有方", SlotMode.HIDE, "",
                false, "", false, 8080, "", 1, true);
    }

    private static LockScreenPrefs prefs(boolean oFollow, String oMode, String oText,
                                         boolean vFollow, String vMode, String vText) {
        return new LockScreenPrefs(oFollow, oMode, oText, vFollow, vMode, vText, false, "");
    }

    @Test
    public void bothFollow_returnsMainReuse() {
        // 全跟随主页且未开整段 → 直接复用主页（行为与未分离完全一致）。
        LockScreenPrefs p = prefs(true, "show", "x", true, "hide", "");
        Config m = main();
        assertSame(m, p.toEffective(m));
    }

    @Test
    public void ownerFollow_vpnIndependent_mergesPerSlot() {
        LockScreenPrefs p = prefs(true, "show", "x", false, "show", "锁屏VPN");
        Config e = p.toEffective(main());
        assertEquals(SlotMode.SHOW, e.ownerMode);
        assertEquals("主页所有方", e.ownerText);
        assertEquals(SlotMode.SHOW, e.vpnMode);
        assertEquals("锁屏VPN", e.vpnText);
    }

    @Test
    public void bothIndependent_usesLockscreenValues() {
        LockScreenPrefs p = prefs(false, "hide", "", false, "show", "锁屏VPN");
        Config e = p.toEffective(main());
        assertEquals(SlotMode.HIDE, e.ownerMode);
        assertEquals(SlotMode.SHOW, e.vpnMode);
        assertEquals("锁屏VPN", e.vpnText);
    }

    @Test
    public void wholeReplace_takesPriorityOverFollow() {
        // 锁屏整段替换开启（即使 owner/vpn 都跟随）→ 以其整句为准。
        LockScreenPrefs p = new LockScreenPrefs(true, "show", "x", true, "hide", "",
                true, "锁屏专属整句");
        Config e = p.toEffective(main());
        assertTrue(e.wholeEnabled());
        assertEquals("锁屏专属整句", e.wholeText);
    }

    @Test
    public void wholeBlank_disabled() {
        // 整段空白则视为未开启 → 走字段方案。
        LockScreenPrefs q = new LockScreenPrefs(false, "default", "", false, "default", "",
                true, "   ");
        Config e = q.toEffective(main());
        assertEquals("", e.wholeText);
    }
}