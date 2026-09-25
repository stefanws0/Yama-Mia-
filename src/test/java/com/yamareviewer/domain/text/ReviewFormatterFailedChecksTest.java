package com.yamareviewer.domain.text;

import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.CheckFights;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ReviewFormatterFailedChecksTest
{
	private static KillReview review(List<String> failedChecks)
	{
		KillLog log = CheckFights.healthySolo();
		return KillReviewAssembler.assemble(log, CheckFights.context(log)).toBuilder().failedChecks(failedChecks).build();
	}

	@Test
	public void failedChecksComeFirst()
	{
		ReviewView view = ReviewFormatter.format(review(List.of("PhaseOrder", "Alternation")));

		ViewSection first = view.getSections().get(0);
		assertEquals("Health checks failed", first.getTitle());
		assertNull(first.getHiddenReason());
		assertTrue(first.getLines().toString(), first.getLines().contains("- PhaseOrder"));
		assertTrue(first.getLines().toString(), first.getLines().contains("- Alternation"));
		assertTrue(first.getLines().toString(), first.getLines().get(first.getLines().size() - 1).contains("Report a problem"));
	}

	@Test
	public void noFailedChecksAddNoSection()
	{
		ReviewView view = ReviewFormatter.format(review(List.of()));

		assertNotEquals("Health checks failed", view.getSections().get(0).getTitle());
	}
}
