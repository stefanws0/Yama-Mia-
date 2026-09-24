package com.yamareviewer.tools;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CaptureSummaryTest
{
	@Test
	public void countsIdsPerActorWithTheirMostCommonGapAndGamevalName()
	{
		String summary = CaptureSummary.summarize(List.of(
			new AnimationObserved(0, Actor.YAMA, 42),
			new AnimationObserved(7, Actor.YAMA, 42),
			new AnimationObserved(14, Actor.YAMA, 42),
			new GraphicObserved(5, Actor.SELF, 3247)),
			(kind, id) -> id == 42 ? Optional.of("NPC_YAMA01_MAGIC01") : Optional.empty());

		assertTrue(summary, summary.contains(String.format(CaptureSummary.ROW, "animation", "YAMA", 42, 3, 0, 14, "7", "NPC_YAMA01_MAGIC01")));
		assertTrue(summary, summary.contains(String.format(CaptureSummary.ROW, "graphic", "SELF", 3247, 1, 5, 5, "-", "")));
	}
}
