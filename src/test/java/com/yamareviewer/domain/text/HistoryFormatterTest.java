package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.history.HistoryView;
import com.yamareviewer.domain.history.KillSummary;
import com.yamareviewer.domain.history.Trend;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.ReviewStatus;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HistoryFormatterTest
{
	private static KillSummary kill(String id, int ticks, EndReason endReason, ReviewStatus status, Long cost)
	{
		return KillSummary.builder().killId(id).startEpochMs(1_700_000_000_000L).killTicks(ticks).endReason(endReason).status(status).cost(cost).build();
	}

	@Test
	public void summaryLinesWithStatisticsAndTrends()
	{
		HistoryView view = new HistoryView(new HistoryKey(Mode.SOLO, Contract.NONE),
			List.of(kill("a", 420, EndReason.YAMA_DIED, ReviewStatus.COMPLETE, 318_450L), kill("b", 400, EndReason.YAMA_DIED, ReviewStatus.COMPLETE, 200_000L)),
			410.0, null, 259_225.0, 400, null,
			new Trend(410.0, 430.0), new Trend(null, null), new Trend(259_225.0, 260_000.0), new Trend(null, null));

		assertEquals(List.of(
			"2 kills",
			"Kill time: avg 4:06, best 4:00 ↓ improving",
			"P3 accuracy: n/a",
			"Cost per kill: avg 259k → steady",
			"Defence drained: n/a"), HistoryFormatter.summaryLines(view));
	}

	@Test
	public void anEmptyViewSaysSo()
	{
		HistoryView view = new HistoryView(new HistoryKey(Mode.SOLO, Contract.NONE), List.of(), null, null, null, null, null,
			new Trend(null, null), new Trend(null, null), new Trend(null, null), new Trend(null, null));

		assertEquals(List.of("No kills yet"), HistoryFormatter.summaryLines(view));
	}

	@Test
	public void killLinesShowTimeOutcomeAndCost()
	{
		assertTrue(HistoryFormatter.killLine(kill("a", 420, EndReason.YAMA_DIED, ReviewStatus.COMPLETE, 318_450L)).endsWith(" · 4:12 · complete · 318k"));
		assertTrue(HistoryFormatter.killLine(kill("b", 420, EndReason.YAMA_DIED, ReviewStatus.INCOMPLETE, null)).endsWith(" · 4:12 · incomplete"));
		assertTrue(HistoryFormatter.killLine(kill("c", 300, EndReason.PLAYER_DIED, ReviewStatus.COMPLETE, 1_000L)).endsWith(" · 3:00 · died · 1k"));
		assertTrue(HistoryFormatter.killLine(kill("d", -1, EndReason.LEFT, ReviewStatus.COMPLETE, null)).endsWith(" · n/a · left"));
	}
}
