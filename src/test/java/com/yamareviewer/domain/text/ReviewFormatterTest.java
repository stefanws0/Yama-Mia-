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
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ReviewFormatterTest
{
	private static ViewSection section(ReviewView view, String title)
	{
		return view.getSections().stream().filter(section -> section.getTitle().equals(title)).findFirst().get();
	}

	@Test
	public void headlineStatusAndSectionOrder()
	{
		ReviewView view = ReviewFormatter.format(Reviews.builder("k", 1L, Mode.DUO_HOST, Contract.BLOODIED_BLOWS).build());

		assertEquals("Yama (duo host, Bloodied Blows) 4:12", view.getHeadline());
		assertEquals("Complete", view.getStatus());
		assertEquals(List.of("Phases", "Damage taken", "Flares", "Supplies"),
			view.getSections().stream().map(ViewSection::getTitle).collect(toList()));
	}

	@Test
	public void phaseDamageFlareAndSupplyLines()
	{
		ReviewView view = ReviewFormatter.format(Reviews.builder("k", 1L, Mode.DUO_HOST, Contract.NONE).build());

		assertEquals(List.of("P1 1:05 (108 ticks)", "Judge 1 0:19 (32 ticks)", "P2 1:10 (117 ticks)", "Judge 2 0:14 (23 ticks)",
			"P3 1:24 (140 ticks)", "Total 4:12 (420 ticks)"), section(view, "Phases").getLines());
		assertEquals(List.of("You: 212", "  P1 22 · P3 190", "  Melee 22", "  Flare explosions 190",
			"Partner: 188", "  P3 188", "  Other 188", "Shared mechanics: 190"), section(view, "Damage taken").getLines());
		assertEquals(List.of("Spawned 6, killed 5, exploded 1", "Yama healed 30", "Wave 1 (summoned): 2 flares, 30 ticks",
			"Wave 2 (summoned): 4 flares, 30 ticks"), section(view, "Flares").getLines());
		assertEquals(List.of("Shark x2: 1,600", "Super restore 6 doses: 316,850", "Total: 318,450 (Grand Exchange)"),
			section(view, "Supplies").getLines());
		assertNull(section(view, "Supplies").getHiddenReason());
	}

	@Test
	public void soloReviewsHaveNoPartnerLines()
	{
		ReviewView view = ReviewFormatter.format(Reviews.sample());

		assertEquals(List.of("You: 212", "  P1 22 · P3 190", "  Melee 22", "  Flare explosions 190", "Shared mechanics: 190"),
			section(view, "Damage taken").getLines());
		assertEquals("Yama (solo) 4:12", view.getHeadline());
	}

	@Test
	public void hiddenSectionsShowTheirReasonAndMakeTheStatusIncomplete()
	{
		KillReview review = Reviews.sample().toBuilder()
			.flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.phases(Section.hidden(HiddenReason.ERROR))
			.build()
			.withRecomputedStatus();

		ReviewView view = ReviewFormatter.format(review);

		assertEquals("Incomplete", view.getStatus());
		assertEquals("Yama (solo) n/a", view.getHeadline());
		assertEquals("IDs not captured", section(view, "Flares").getHiddenReason());
		assertEquals(List.of(), section(view, "Flares").getLines());
		assertEquals("Error while reviewing", section(view, "Phases").getHiddenReason());
	}

	@Test
	public void aDeathPutsTheRecapFirstWithItsTicks()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.SOLO, Contract.FORFEIT_BREATH)
			.endReason(EndReason.PLAYER_DIED)
			.deathRecap(Section.ok(new DeathRecap(List.of(
				new RecapTick(419, List.of(new RecapHit(DamageSource.MELEE, 22)), 12, 5, 3, "you", List.of("Shark")),
				new RecapTick(420, List.of(), 0, 5, 0, "partner", List.of())), true)))
			.build();

		ReviewView view = ReviewFormatter.format(review);

		assertEquals("Yama (solo, Forfeit Breath) died in P3 at 4:12", view.getHeadline());
		assertEquals("Death recap", view.getSections().get(0).getTitle());
		assertEquals(List.of(
			"Tick 419: HP 12, prayer 5, run 3%, Yama targets you, hit 22 (Melee), used Shark",
			"Tick 420: HP 0, prayer 5, run 0%, Yama targets partner",
			"Died out of run energy"), view.getSections().get(0).getLines());
	}

	@Test
	public void aLeftKillSaysWhereItStopped()
	{
		KillReview review = Reviews.sample().toBuilder().endReason(EndReason.LEFT).status(ReviewStatus.COMPLETE).build();

		assertEquals("Yama (solo) left in P3 at 4:12", ReviewFormatter.format(review).getHeadline());
	}
}
