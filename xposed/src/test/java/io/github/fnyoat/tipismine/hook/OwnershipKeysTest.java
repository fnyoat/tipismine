package io.github.fnyoat.tipismine.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OwnershipKeysTest {

    @Test
    public void matchesKnownKeys() {
        assertTrue(OwnershipKeys.matches("vpn_connected"));
        assertTrue(OwnershipKeys.matches("connected_to_vpn"));
        assertTrue(OwnershipKeys.matches("device_owner_vpn_connected"));
        assertTrue(OwnershipKeys.matches("managed_device_vpn_connected"));
        assertTrue(OwnershipKeys.matches("work_profile_vpn_connected"));
        assertTrue(OwnershipKeys.matches("personal_vpn_connected"));
        assertTrue(OwnershipKeys.matches("owner_connected_to_vpn"));
    }

    @Test
    public void matchesWithPrefixOrSuffix() {
        assertTrue(OwnershipKeys.matches("android_owner_vpn_connected_summary"));
        assertTrue(OwnershipKeys.matches("vpn_connected_explanation"));
        assertTrue(OwnershipKeys.matches("prefix_connected_to_vpn"));
    }

    @Test
    public void doesNotMatchUnrelated() {
        assertFalse(OwnershipKeys.matches(""));
        assertFalse(OwnershipKeys.matches(null));
        assertFalse(OwnershipKeys.matches("some_other_string"));
        assertFalse(OwnershipKeys.matches("wifi_connected"));
        assertFalse(OwnershipKeys.matches("vpn_spacer_only"));
        assertFalse(OwnershipKeys.matches("connected_to"));
    }

    @Test
    public void matchesTemplateEnglishAosp() {
        assertTrue(OwnershipKeys.matchesTemplate(
                "This device belongs to %1$s and is connected to the internet through %2$s"));
        assertTrue(OwnershipKeys.matchesTemplate(
                "This device is managed by %1$s and connected to the internet through %2$s"));
    }

    @Test
    public void matchesTemplateChinese() {
        assertTrue(OwnershipKeys.matchesTemplate("本设备属于 %1$s，通过网络 %2$s 连接到互联网"));
        assertTrue(OwnershipKeys.matchesTemplate("设备属于 %1$s，并通过 vpn 连接"));
    }

    @Test
    public void matchesTemplateMiuiRealValue() {
        // MIUI 实际资源值（从 resources.arsc 提取）
        assertTrue(OwnershipKeys.matchesTemplate(
                "This device belongs to %1$s and is connected to the internet through %2$s"));
    }

    @Test
    public void doesNotMatchTemplateUnrelated() {
        assertFalse(OwnershipKeys.matchesTemplate(""));
        assertFalse(OwnershipKeys.matchesTemplate(null));
        assertFalse(OwnershipKeys.matchesTemplate("Notifications are turned off"));
        assertFalse(OwnershipKeys.matchesTemplate("Connected to Wi-Fi"));
        assertFalse(OwnershipKeys.matchesTemplate("通过 Wi-Fi 可以上网"));
        assertFalse(OwnershipKeys.matchesTemplate("vpn is available"));
    }

    @Test
    public void matchesCustomList() {
        assertTrue(OwnershipKeys.matchesCustom("quick_settings_disclosure_named_management_named_vpn",
                "quick_settings_disclosure_named_management_named_vpn"));
        assertTrue(OwnershipKeys.matchesCustom("x_do_disclosure_with_name_y",
                "do_disclosure_with_name"));
        assertTrue(OwnershipKeys.matchesCustom("miui_owner_info",
                "owner_info, other_key\nmiui_owner_info"));
        // 大小写不敏感
        assertTrue(OwnershipKeys.matchesCustom("Foo-OWNER_BAR", "owner_bar"));
    }

    @Test
    public void doesNotMatchCustomEmpty() {
        assertFalse(OwnershipKeys.matchesCustom("anything", ""));
        assertFalse(OwnershipKeys.matchesCustom("anything", null));
        assertFalse(OwnershipKeys.matchesCustom(null, "some_key"));
        assertFalse(OwnershipKeys.matchesCustom("wifi_connected", "vpn_connected"));
    }

    @Test
    public void splitCustomKeysDeduplicatesAndTrims() {
        String[] out = OwnershipKeys.splitCustomKeys(" a , a, b\n,c,，b");
        assertEquals(3, out.length);
        assertEquals("a", out[0]);
        assertEquals("b", out[1]);
        assertEquals("c", out[2]);
    }

    @Test
    public void splitCustomKeysEmpty() {
        assertEquals(0, OwnershipKeys.splitCustomKeys(null).length);
        assertEquals(0, OwnershipKeys.splitCustomKeys("").length);
        assertEquals(0, OwnershipKeys.splitCustomKeys("  \n  ").length);
    }

    @Test
    public void matchesActive_onlyIteratesActiveEntries() {
        // 仅活跃条目（本机存在）参与匹配；不存在的 key 根本不进遍历
        String[][] active = { {"vpn_connected", "AOSP"} };
        assertTrue(OwnershipKeys.matchesActive("quick_settings_vpn_connected_label", active));
        assertFalse(OwnershipKeys.matchesActive("do_disclosure_with_name", active));
        assertFalse(OwnershipKeys.matchesActive(null, active));
        assertFalse(OwnershipKeys.matchesActive("vpn_connected", null));
        assertFalse(OwnershipKeys.matchesActive("vpn_connected", new String[0][]));
    }

    @Test
    public void entryNamesToSet_bridgesEntries() {
        String[][] entries = { {"vpn_connected", "AOSP"}, {"do_disclosure_with_name", "MIUI/HyperOS"} };
        java.util.Set<String> names = OwnershipKeys.entryNamesToSet(entries);
        assertEquals(2, names.size());
        assertTrue(names.contains("vpn_connected"));
        assertTrue(names.contains("do_disclosure_with_name"));
        assertTrue(OwnershipKeys.isActive("vpn_connected", names));
        assertFalse(OwnershipKeys.isActive("nope", names));
        assertFalse(OwnershipKeys.isActive("vpn_connected", null));
    }
}
