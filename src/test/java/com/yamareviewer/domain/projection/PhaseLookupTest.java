package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class PhaseLookupTest
{
	private final PhaseTimes phases = Fights.phases(100);

	@Test
	public void aTickBelongsToTheLastSpanThatStartedBeforeIt()
	{
		assertEquals(Phase.P1, PhaseLookup.phaseAt(phases, 0));
		assertEquals(Phase.P1, PhaseLookup.phaseAt(phases, 9));
		assertEquals(Phase.JUDGE_1, PhaseLookup.phaseAt(phases, 10));
		assertEquals(Phase.P2, PhaseLookup.phaseAt(phases, 20));
		assertEquals(Phase.P3, PhaseLookup.phaseAt(phases, 40));
		assertEquals(Phase.P3, PhaseLookup.phaseAt(phases, 100));
	}

	@Test
	public void outsideEverySpanIsNull()
	{
		assertNull(PhaseLookup.phaseAt(phases, -1));
		assertNull(PhaseLookup.phaseAt(phases, 101));
		assertNull(PhaseLookup.phaseAt(Section.<PhaseTimes>hidden(HiddenReason.IDS_NOT_CAPTURED), 5));
	}

	@Test
	public void upstreamReportsTheFirstHiddenReason()
	{
		assertEquals(java.util.Optional.empty(), Upstream.hidden(Section.ok(1), Section.ok("a")));
		assertEquals(java.util.Optional.of(HiddenReason.ERROR),
			Upstream.hidden(Section.ok(1), Section.hidden(HiddenReason.ERROR), Section.hidden(HiddenReason.CONTRACT)));
	}
}
