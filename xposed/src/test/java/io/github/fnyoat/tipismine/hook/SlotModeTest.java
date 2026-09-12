package io.github.fnyoat.tipismine.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SlotModeTest {

    @Test
    public void fromParsesKnownValues() {
        assertEquals(SlotMode.DEFAULT, SlotMode.from("default"));
        assertEquals(SlotMode.SHOW, SlotMode.from("show"));
        assertEquals(SlotMode.HIDE, SlotMode.from("hide"));
    }

    @Test
    public void fromFallsBackToDefaultOnUnknown() {
        assertEquals(SlotMode.DEFAULT, SlotMode.from(null));
        assertEquals(SlotMode.DEFAULT, SlotMode.from(""));
        assertEquals(SlotMode.DEFAULT, SlotMode.from("banana"));
        assertEquals(SlotMode.DEFAULT, SlotMode.from("SHOW"));
    }

    @Test
    public void predicatesAreExclusive() {
        assertTrue(SlotMode.isDefault(SlotMode.DEFAULT));
        assertFalse(SlotMode.isShow(SlotMode.DEFAULT));
        assertFalse(SlotMode.isHide(SlotMode.DEFAULT));

        assertTrue(SlotMode.isShow(SlotMode.SHOW));
        assertFalse(SlotMode.isDefault(SlotMode.SHOW));
        assertFalse(SlotMode.isHide(SlotMode.SHOW));

        assertTrue(SlotMode.isHide(SlotMode.HIDE));
        assertFalse(SlotMode.isDefault(SlotMode.HIDE));
        assertFalse(SlotMode.isShow(SlotMode.HIDE));
    }

    @Test
    public void constantsMatchStrings() {
        assertEquals("default", SlotMode.MODE_DEFAULT);
        assertEquals("show", SlotMode.MODE_SHOW);
        assertEquals("hide", SlotMode.MODE_HIDE);
    }
}