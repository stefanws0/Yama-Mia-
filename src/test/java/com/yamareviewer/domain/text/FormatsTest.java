package com.yamareviewer.domain.text;

import com.yamareviewer.domain.history.Trend;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FormatsTest
{
	@Test
	public void durationsRoundTicksToSeconds()
	{
		assertEquals("4:12", Formats.duration(420));
		assertEquals("1:05", Formats.duration(108));
		assertEquals("0:00", Formats.duration(0));
		assertEquals("10:00", Formats.duration(1000));
	}

	@Test
	public void goldFormats()
	{
		assertEquals("318,450", Formats.gp(318_450));
		assertEquals("450", Formats.compactGp(450));
		assertEquals("318k", Formats.compactGp(318_450));
		assertEquals("1M", Formats.compactGp(1_000_000));
		assertEquals("1.25M", Formats.compactGp(1_250_000));
		assertEquals("2.5M", Formats.compactGp(2_500_000));
		assertEquals("89%", Formats.percent(0.894));
	}

	@Test
	public void trendsArrowsUseATwoPercentBand()
	{
		assertEquals("↓ improving", Formats.trend(new Trend(250.0, 260.0), true));
		assertEquals("↑ declining", Formats.trend(new Trend(270.0, 260.0), true));
		assertEquals("→ steady", Formats.trend(new Trend(261.0, 260.0), true));
		assertEquals("↑ improving", Formats.trend(new Trend(0.9, 0.8), false));
		assertEquals("", Formats.trend(new Trend(0.9, null), false));
		assertEquals("", Formats.trend(null, false));
	}

	@Test
	public void datesHaveMinutePrecision()
	{
		assertEquals(16, Formats.date(1_700_000_000_000L).length());
	}
}
