package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PhaseTimesTest
{
	private final PhaseTimes times = new PhaseTimes(List.of(
		new PhaseSpan(Phase.P1, 0, 50),
		new PhaseSpan(Phase.JUDGE_1, 50, 80),
		new PhaseSpan(Phase.P2, 80, 130)), 130);

	@Test
	public void spanDurations()
	{
		PhaseSpan judge = times.span(Phase.JUDGE_1).get();

		assertEquals(30, judge.ticks());
		assertEquals(18.0, judge.seconds(), 0.001);
		assertEquals(Optional.empty(), times.span(Phase.P3));
		assertEquals(Optional.of(new PhaseSpan(Phase.P2, 80, 130)), times.last());
	}

	@Test
	public void phaseAtUsesHalfOpenSpansExceptTheLastOne()
	{
		assertEquals(Optional.of(Phase.P1), times.phaseAt(0));
		assertEquals(Optional.of(Phase.P1), times.phaseAt(49));
		assertEquals(Optional.of(Phase.JUDGE_1), times.phaseAt(50));
		assertEquals(Optional.of(Phase.P2), times.phaseAt(80));
		assertEquals(Optional.of(Phase.P2), times.phaseAt(130));
		assertEquals(Optional.empty(), times.phaseAt(131));
		assertEquals(Optional.empty(), times.phaseAt(-1));
	}
}
