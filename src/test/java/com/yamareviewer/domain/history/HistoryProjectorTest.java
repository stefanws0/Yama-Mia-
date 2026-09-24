package com.yamareviewer.domain.history;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplySummary;
import com.yamareviewer.testing.Reviews;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class HistoryProjectorTest
{
	private static final HistoryKey SOLO_NONE = new HistoryKey(Mode.SOLO, Contract.NONE);

	private static KillReview kill(String id, long start, int ticks, long cost, EndReason endReason)
	{
		return Reviews.builder(id, start, Mode.SOLO, Contract.NONE)
			.endReason(endReason)
			.phases(Section.ok(new PhaseTimes(List.of(new PhaseSpan(Phase.P1, 0, ticks)), ticks)))
			.supplies(Section.ok(new SupplySummary(List.of(), cost, PriceMode.GRAND_EXCHANGE)))
			.build();
	}

	private static List<String> ids(HistoryView view)
	{
		return view.getKills().stream().map(KillSummary::getKillId).collect(toList());
	}

	@Test
	public void summarizeTakesTicksCostAndStatus()
	{
		KillSummary summary = HistoryProjector.summarize(Reviews.sample());

		assertEquals(KillSummary.builder().killId("kill-1").startEpochMs(1_700_000_000_000L).killTicks(420).endReason(EndReason.YAMA_DIED)
			.status(ReviewStatus.COMPLETE).cost(318_450L).p3Accuracy(null).defenceDrained(null).specLandedShare(null).build(), summary);
	}

	@Test
	public void hiddenPhasesOrSuppliesGiveUnknownTicksAndCost()
	{
		KillReview review = Reviews.builder("k", 1L, Mode.SOLO, Contract.NONE)
			.phases(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.build();

		KillSummary summary = HistoryProjector.summarize(review);

		assertEquals(-1, summary.getKillTicks());
		assertNull(summary.getCost());
	}

	@Test
	public void indexGroupsByModeAndContractNewestFirstAndLimits()
	{
		List<KillReview> reviews = List.of(
			kill("a", 1_000L, 400, 100, EndReason.YAMA_DIED),
			kill("c", 3_000L, 400, 100, EndReason.YAMA_DIED),
			kill("b", 2_000L, 400, 100, EndReason.YAMA_DIED),
			Reviews.builder("bb", 500L, Mode.SOLO, Contract.BLOODIED_BLOWS).build(),
			Reviews.builder("h", 100L, Mode.DUO_HOST, Contract.NONE).build());

		HistoryIndex index = HistoryProjector.index(reviews, 2);

		assertEquals(List.of("c", "b"), ids(index.view(SOLO_NONE).get()));
		assertEquals(List.of("bb"), ids(index.view(new HistoryKey(Mode.SOLO, Contract.BLOODIED_BLOWS)).get()));
		assertEquals(List.of("h"), ids(index.view(new HistoryKey(Mode.DUO_HOST, Contract.NONE)).get()));
		assertEquals(Optional.empty(), index.view(new HistoryKey(Mode.DUO_JOINER, Contract.NONE)));
		assertEquals(SOLO_NONE, index.view(SOLO_NONE).get().getKey());
	}

	@Test
	public void averagesAndPersonalBests()
	{
		HistoryView view = HistoryProjector.index(List.of(
			kill("a", 1_000L, 420, 100, EndReason.YAMA_DIED),
			kill("b", 2_000L, 400, 200, EndReason.YAMA_DIED),
			kill("c", 3_000L, 500, 300, EndReason.YAMA_DIED)), 50).view(SOLO_NONE).get();

		assertEquals(440.0, view.getAverageKillTicks(), 0.001);
		assertEquals(Integer.valueOf(400), view.getFastestKillTicks());
		assertEquals(200.0, view.getAverageCost(), 0.001);
		assertNull(view.getAverageP3Accuracy());
		assertNull(view.getBestP3Accuracy());
		assertNull(view.getKillTicksTrend().getPrevious10Mean());
		assertEquals(440.0, view.getKillTicksTrend().getLast10Mean(), 0.001);
		assertEquals(new Trend(null, null), view.getP3AccuracyTrend());
		assertEquals(new Trend(null, null), view.getDefenceDrainedTrend());
	}

	@Test
	public void deathsAndUnknownTicksAreLeftOutOfTheTimeStatistics()
	{
		KillReview unknownTicks = Reviews.builder("u", 3_000L, Mode.SOLO, Contract.NONE).phases(Section.hidden(HiddenReason.ERROR)).build();

		HistoryView view = HistoryProjector.index(List.of(
			kill("a", 1_000L, 400, 100, EndReason.YAMA_DIED),
			kill("d", 2_000L, 300, 100, EndReason.PLAYER_DIED),
			unknownTicks), 50).view(SOLO_NONE).get();

		assertEquals(List.of("u", "d", "a"), ids(view));
		assertEquals(400.0, view.getAverageKillTicks(), 0.001);
		assertEquals(Integer.valueOf(400), view.getFastestKillTicks());
		assertEquals(3, view.getKills().size());
	}

	@Test
	public void noFinishedKillsMeansNoTimeStatistics()
	{
		HistoryView view = HistoryProjector.index(List.of(kill("d", 2_000L, 300, 100, EndReason.PLAYER_DIED)), 50).view(SOLO_NONE).get();

		assertNull(view.getAverageKillTicks());
		assertNull(view.getFastestKillTicks());
		assertEquals(new Trend(null, null), view.getKillTicksTrend());
		assertEquals(100.0, view.getAverageCost(), 0.001);
	}

	@Test
	public void trendsCompareTheLastTenKillsWithTheTenBefore()
	{
		List<KillReview> reviews = new ArrayList<>();
		for (int i = 0; i < 25; i++)
		{
			reviews.add(kill("k" + i, 1_000L * (25 - i), 400 + i, 1_000 + i, EndReason.YAMA_DIED));
		}

		HistoryView view = HistoryProjector.index(reviews, 50).view(SOLO_NONE).get();

		assertEquals(new Trend(404.5, 414.5), view.getKillTicksTrend());
		assertEquals(new Trend(1_004.5, 1_014.5), view.getCostTrend());
		assertEquals(25, view.getKills().size());
	}
}
