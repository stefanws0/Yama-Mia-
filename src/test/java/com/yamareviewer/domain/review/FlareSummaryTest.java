package com.yamareviewer.domain.review;

import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FlareSummaryTest
{
	@Test
	public void countsAcrossWaves()
	{
		FlareSummary summary = new FlareSummary(List.of(
			new FlareWave(30, 60, List.of(
				new FlareResult(1, 30, 45, FlareCause.SUMMONED, FlareFate.KILLED, true),
				new FlareResult(2, 30, 60, FlareCause.SUMMONED, FlareFate.EXPLODED, false))),
			new FlareWave(200, 230, List.of(
				new FlareResult(3, 200, null, FlareCause.MELEE, FlareFate.UNKNOWN, false)))), 30);

		assertEquals(3, summary.spawned());
		assertEquals(1, summary.killed());
		assertEquals(1, summary.exploded());
		assertEquals(1, summary.purgingStaffKills());
		assertEquals(FlareCause.SUMMONED, summary.getWaves().get(0).cause());
		assertEquals(30, summary.getWaves().get(0).ticks());
		assertEquals(FlareCause.UNKNOWN, new FlareWave(0, 0, List.of()).cause());
	}
}
