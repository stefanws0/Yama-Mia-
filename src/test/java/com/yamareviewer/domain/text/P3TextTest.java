package com.yamareviewer.domain.text;

import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.P3Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class P3TextTest
{
	private final KillReview sample = P3Reviews.sample();
	private final KillReview hidden = P3Reviews.allHidden();

	@Test
	public void chatLineTwo()
	{
		assertEquals("P3 prayers 2/3 blocked (1 too early). Crash lines 2/3 dodged. Waves 1/2.", P3Text.prayerLine(sample));
		assertEquals("P3 prayers n/a. Crash lines n/a. Waves n/a.", P3Text.prayerLine(hidden));
	}

	@Test
	public void specFragmentOfChatLineFour()
	{
		assertEquals("Def 225→147 in P1 (1/1 Elder maul)", P3Text.specFragment(sample));
		assertEquals("n/a", P3Text.specFragment(hidden));
	}

	@Test
	public void openerSection()
	{
		ViewSection section = P3Text.opener(sample);

		assertEquals("Opener", section.getTitle());
		assertNull(section.getHiddenReason());
		assertEquals(List.of(
			"P2 glyphs: 3 fire, 1 shadow: magic opener expected.",
			"Yama's first P3 attack: magic on you, as expected.",
			"Your first P3 attack: blocked."), section.getLines());
	}

	@Test
	public void prayerTimelineSection()
	{
		ViewSection section = P3Text.prayerTimeline(sample);

		assertEquals("P3 prayers", section.getTitle());
		assertEquals(List.of(
			"2/3 blocked, 1 mistake (1 too early).",
			"#1 t40 magic: blocked",
			"#2 t47 ranged: too early (switched after 4 ticks)",
			"#3 t54 magic: blocked (switched after 7 ticks)"), section.getLines());
	}

	@Test
	public void crashAndWaveSections()
	{
		assertEquals(List.of(
			"You: 2/3 dodged, 12 damage.",
			"Set 1 line 1 t50: dodged",
			"Set 1 line 2 t54: hit (12)",
			"Set 1 line 3 t58: dodged"), P3Text.crashLines(sample).getLines());
		assertEquals(List.of(
			"You: 1/2 dodged, 15 damage, prayers disabled 1x.",
			"t60: hit (15), prayers disabled",
			"t64: dodged"), P3Text.waves(sample).getLines());
	}

	@Test
	public void specsSection()
	{
		ViewSection section = P3Text.specs(sample);

		assertEquals("Specs and drains", section.getTitle());
		assertEquals(List.of(
			"Defence 225→147 (lowest, in P1), 78 of 80 drained (modelled).",
			"Elder maul: 1/1 landed, 60 avg damage",
			"Spec energy per Defence point drained: 0.6%.",
			"t2 Elder maul: Def 225→147"), section.getLines());
	}

	@Test
	public void tickLogSection()
	{
		ViewSection section = P3Text.tickLog(sample);

		assertEquals("P3 tick log", section.getTitle());
		assertEquals(List.of(
			"Gaps of 7 ticks: 1/2.",
			"t40 magic → you",
			"t47 ranged → you (+7)",
			"t56 magic → you (+9: crash line at 50)"), section.getLines());
	}

	@Test
	public void hiddenSectionsCarryTheirReasonAndNoLines()
	{
		ViewSection section = P3Text.prayerTimeline(hidden);

		assertTrue(section.getLines().isEmpty());
		assertEquals(P3Text.hiddenText(HiddenReason.IDS_NOT_CAPTURED), section.getHiddenReason());
		assertEquals(P3Text.hiddenText(HiddenReason.CONTRACT), P3Text.opener(hidden).getHiddenReason());
		assertEquals("Hidden: game IDs not captured", P3Text.hiddenText(HiddenReason.IDS_NOT_CAPTURED));
	}
}
