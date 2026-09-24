package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KillReviewP3Test
{
	private static KillReview.KillReviewBuilder part2Only()
	{
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId("k")
			.startEpochMs(1L)
			.endEpochMs(2L)
			.endReason(EndReason.YAMA_DIED)
			.mode(Mode.SOLO)
			.contract(Contract.NONE)
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(0)
			.phases(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.damage(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.flares(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.deathRecap(Section.hidden(HiddenReason.NOT_APPLICABLE));
	}

	@Test
	public void missingPart3FieldsReadAsNotApplicable()
	{
		KillReview review = part2Only().build();

		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getPrayerReview().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getOpener().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getTickLog().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getCrashes().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getWaves().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getSpecs().hiddenReason());
	}

	@Test
	public void part3SectionsCountTowardsTheStatus()
	{
		KillReview review = part2Only().specs(Section.hidden(HiddenReason.ERROR)).build().withRecomputedStatus();

		assertEquals(ReviewStatus.INCOMPLETE, review.getStatus());
		assertEquals(11, review.allSections().size());
	}

	@Test
	public void allSectionsAreInDisplayOrder()
	{
		Section<TickLog> tickLog = Section.ok(new TickLog(List.of(), 7));
		KillReview review = part2Only().tickLog(tickLog).build();

		List<Section<?>> sections = review.allSections();

		assertTrue(sections.contains(tickLog));
		assertEquals(sections.size() - 2, sections.indexOf(tickLog));
	}
}
