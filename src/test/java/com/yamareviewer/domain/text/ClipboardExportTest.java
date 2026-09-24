package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ClipboardExportTest
{
	@Test
	public void chatLinesThenSpecsThenSuppliesThenTheRecap()
	{
		KillReview review = Reviews.sample().toBuilder()
			.endReason(EndReason.PLAYER_DIED)
			.deathRecap(Section.ok(new DeathRecap(List.of(new RecapTick(420, List.of(), 0, 5, null, "you", List.of())), false)))
			.build();

		String text = ClipboardExport.text(review);

		assertEquals(String.join("\n",
			"Yama (solo) died in P3 at 4:12. P1 1:05, P2 1:10, P3 1:24.",
			"P3 prayers n/a. Crash lines n/a. Waves n/a.",
			"Flares 5/6 killed. Damage taken 212.",
			"Specs: n/a. Supplies 318k.",
			"",
			"Specs and drains",
			"n/a (Not applicable to this kill)",
			"",
			"Supplies",
			"Shark x2: 1,600",
			"Super restore 6 doses: 316,850",
			"Total: 318,450 (Grand Exchange)",
			"",
			"Death recap",
			"Tick 420: HP 0, prayer 5, Yama targets you"), text);
	}

	@Test
	public void noRecapBlockWithoutADeathAndNoNames()
	{
		String text = ClipboardExport.text(Reviews.sample());

		assertFalse(text.contains("Death recap"));
		assertTrue(text.endsWith("Total: 318,450 (Grand Exchange)"));
		assertFalse(text.contains("Me"));
		assertFalse(text.contains("Buddy"));
	}
}
