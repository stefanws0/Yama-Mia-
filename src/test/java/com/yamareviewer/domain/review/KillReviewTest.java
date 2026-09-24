package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillReviewTest
{
	private static KillReview.KillReviewBuilder review()
	{
		return Reviews.builder("kill-1", 1_000L, Mode.SOLO, Contract.NONE);
	}

	@Test
	public void sectionsAreListedInDisplayOrder()
	{
		KillReview review = review().build();

		assertEquals(List.of(review.getPhases(), review.getDamage(), review.getFlares(), review.getSupplies(), review.getDeathRecap()),
			review.allSections());
	}

	@Test
	public void onlyHealthCheckFailuresErrorsAndSkippedEventsMakeAReviewIncomplete()
	{
		assertEquals(ReviewStatus.COMPLETE, review().build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.COMPLETE,
			review().flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED)).supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
				.damage(Section.hidden(HiddenReason.CONTRACT)).build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.INCOMPLETE,
			review().flares(Section.hidden(HiddenReason.ERROR)).build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.INCOMPLETE,
			review().phases(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED)).build().withRecomputedStatus().getStatus());
		assertEquals(ReviewStatus.INCOMPLETE, review().skippedEvents(1).build().withRecomputedStatus().getStatus());
	}

	@Test
	public void aSectionMissingFromAStoredReviewReadsAsNotApplicable()
	{
		KillReview review = review().deathRecap(null).build();

		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), review.getDeathRecap());
		assertEquals(5, review.allSections().size());
	}
}
