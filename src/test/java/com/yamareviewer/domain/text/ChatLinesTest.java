package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ChatLinesTest
{
	private static final ChatLineOptions ALL = new ChatLineOptions(true, true, true, true, true);

	@Test
	public void theFourLinesOfSpec72()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.DUO_HOST, Contract.BLOODIED_BLOWS).build();

		assertEquals(List.of(
			"Yama (duo host, Bloodied Blows) 4:12. P1 1:05, P2 1:10, P3 1:24.",
			"P3 prayers n/a. Crash lines n/a. Waves n/a.",
			"Flares 5/6 killed. Damage taken 212 (partner 188).",
			"Specs: n/a. Supplies 318k."), ChatLines.lines(review, ALL));
	}

	@Test
	public void soloAndNoContract()
	{
		List<String> lines = ChatLines.lines(Reviews.sample(), ALL);

		assertEquals("Yama (solo) 4:12. P1 1:05, P2 1:10, P3 1:24.", lines.get(0));
		assertEquals("Flares 5/6 killed. Damage taken 212.", lines.get(2));
	}

	@Test
	public void unknownContractIsNamedAsSuch()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.SOLO, Contract.UNKNOWN_CONTRACT).build();

		assertEquals("Yama (solo, unknown contract) 4:12. P1 1:05, P2 1:10, P3 1:24.", ChatLines.lines(review, ALL).get(0));
	}

	@Test
	public void eachLineCanBeSwitchedOff()
	{
		KillReview review = Reviews.sample();

		assertEquals(List.of("Specs: n/a. Supplies 318k."), ChatLines.lines(review, new ChatLineOptions(false, false, false, true, false)));
		assertEquals(List.of(), ChatLines.lines(review, new ChatLineOptions(false, false, false, false, true)));
	}

	@Test
	public void hiddenValuesPrintAsNotAvailable()
	{
		KillReview review = Reviews.sample().toBuilder()
			.phases(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED))
			.flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.damage(Section.hidden(HiddenReason.ERROR))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.build();

		assertEquals(List.of(
			"Yama (solo) n/a.",
			"P3 prayers n/a. Crash lines n/a. Waves n/a.",
			"Flares n/a. Damage taken n/a.",
			"Specs: n/a. Supplies n/a."), ChatLines.lines(review, ALL));
	}

	@Test
	public void theDeathRecapBlockFollowsWhenEnabledAndPresent()
	{
		KillReview review = Reviews.sample().toBuilder()
			.endReason(EndReason.PLAYER_DIED)
			.deathRecap(Section.ok(new DeathRecap(List.of(
				new RecapTick(420, List.of(new RecapHit(DamageSource.FLARE, 45)), 0, 5, null, "you", List.of())), false)))
			.build();

		List<String> lines = ChatLines.lines(review, ALL);

		assertEquals("Yama (solo) died in P3 at 4:12. P1 1:05, P2 1:10, P3 1:24.", lines.get(0));
		assertEquals(List.of("Death recap:", "Tick 420: HP 0, prayer 5, Yama targets you, hit 45 (Flare explosions)"), lines.subList(4, 6));
		assertEquals(4, ChatLines.lines(review, new ChatLineOptions(true, true, true, true, false)).size());
		assertEquals(4, ChatLines.lines(Reviews.sample(), ALL).size());
	}
}
