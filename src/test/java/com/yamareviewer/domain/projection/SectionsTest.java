package com.yamareviewer.domain.projection;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SectionsTest
{
	@Test
	public void part3KeysHaveStableNames()
	{
		assertEquals("glyphs", Sections.GLYPHS.name());
		assertEquals("attacks", Sections.ATTACKS.name());
		assertEquals("crashes", Sections.CRASHES.name());
		assertEquals("waves", Sections.WAVES.name());
		assertEquals("specs", Sections.SPECS.name());
		assertEquals("prayer-review", Sections.PRAYER_REVIEW.name());
		assertEquals("opener", Sections.OPENER.name());
		assertEquals("tick-log", Sections.TICK_LOG.name());
	}
}
