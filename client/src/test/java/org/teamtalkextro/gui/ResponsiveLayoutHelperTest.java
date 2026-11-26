package org.teamtalkextro.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;
import org.junit.Test;

public class ResponsiveLayoutHelperTest {

    @Test
    public void testServerGridSpanBreakpoints() {
        assertEquals(1, ResponsiveLayoutHelper.computeServerGridSpan(320));
        assertEquals(1, ResponsiveLayoutHelper.computeServerGridSpan(411));
        assertEquals(1, ResponsiveLayoutHelper.computeServerGridSpan(599));

        assertEquals(2, ResponsiveLayoutHelper.computeServerGridSpan(600));
        assertEquals(2, ResponsiveLayoutHelper.computeServerGridSpan(768));
        assertEquals(2, ResponsiveLayoutHelper.computeServerGridSpan(959));

        assertEquals(3, ResponsiveLayoutHelper.computeServerGridSpan(960));
        assertEquals(3, ResponsiveLayoutHelper.computeServerGridSpan(1280));
    }

    @Test
    public void testTabletWidthAndContentMaxWidthPx() {
        assertFalse(ResponsiveLayoutHelper.isTabletWidth(360));
        assertFalse(ResponsiveLayoutHelper.isTabletWidth(599));
        assertTrue(ResponsiveLayoutHelper.isTabletWidth(600));
        assertTrue(ResponsiveLayoutHelper.isTabletWidth(800));

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                ResponsiveLayoutHelper.computeMaxContentWidthPx(411, 760, 2.5f));
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                ResponsiveLayoutHelper.computeMaxContentWidthPx(760, 760, 2.0f));
        assertEquals(1520,
                ResponsiveLayoutHelper.computeMaxContentWidthPx(1024, 760, 2.0f));
    }
}
