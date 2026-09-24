package com.yamareviewer.domain.history;

import com.yamareviewer.testing.P3Reviews;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class HistoryProjectorP3Test
{
	@Test
	public void summarizeFillsThePart3Fields()
	{
		KillSummary summary = HistoryProjector.summarize(P3Reviews.sample());

		assertEquals(Double.valueOf(2.0 / 3.0), summary.getP3Accuracy());
		assertEquals(Integer.valueOf(78), summary.getDefenceDrained());
		assertEquals(Double.valueOf(1.0), summary.getSpecLandedShare());
	}

	@Test
	public void hiddenSectionsLeaveThemNull()
	{
		KillSummary summary = HistoryProjector.summarize(P3Reviews.allHidden());

		assertNull(summary.getP3Accuracy());
		assertNull(summary.getDefenceDrained());
		assertNull(summary.getSpecLandedShare());
	}
}
