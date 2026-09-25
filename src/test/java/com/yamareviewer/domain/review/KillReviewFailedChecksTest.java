package com.yamareviewer.domain.review;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillReviewFailedChecksTest
{
	@Test
	public void failedChecksNeverReadNull()
	{
		assertEquals(List.of(), KillReview.builder().build().getFailedChecks());
		assertEquals(List.of("PhaseOrder"), KillReview.builder().failedChecks(List.of("PhaseOrder")).build().getFailedChecks());
	}

	@Test
	public void reviewsStoredBeforePart4LoadWithNoFailedChecks()
	{
		KillReview review = new Gson().fromJson("{\"reviewSchemaVersion\":1,\"killId\":\"k\"}", KillReview.class);

		assertEquals(List.of(), review.getFailedChecks());
	}
}
