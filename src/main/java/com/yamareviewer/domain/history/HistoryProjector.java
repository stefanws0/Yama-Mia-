package com.yamareviewer.domain.history;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.SpecSummary;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/** Derives the history of spec 7.3 from stored reviews. Pure. */
public final class HistoryProjector
{
	static final int TREND_WINDOW = 10;

	private HistoryProjector()
	{
	}

	/** Part 3 fills the Part 3 fields of KillSummary here. */
	public static KillSummary summarize(KillReview review)
	{
		return KillSummary.builder()
			.killId(review.getKillId())
			.startEpochMs(review.getStartEpochMs())
			.killTicks(review.getPhases().asOptional().map(PhaseTimes::getTotalTicks).orElse(-1))
			.endReason(review.getEndReason())
			.status(review.getStatus())
			.cost(review.getSupplies().asOptional().map(SupplySummary::getTotalCost).orElse(null))
			.p3Accuracy(review.getPrayerReview().asOptional().flatMap(PrayerReview::accuracy).orElse(null))
			.defenceDrained(review.getSpecs().asOptional().map(SpecSummary::defenceDrained).orElse(null))
			.specLandedShare(review.getSpecs().asOptional().flatMap(SpecSummary::landedShare).orElse(null))
			.build();
	}

	public static HistoryIndex index(List<KillReview> reviews, int historySize)
	{
		List<KillReview> newestFirst = new ArrayList<>(reviews);
		newestFirst.sort(Comparator.comparingLong(KillReview::getStartEpochMs).reversed());
		Map<HistoryKey, List<KillSummary>> byKey = new LinkedHashMap<>();
		for (KillReview review : newestFirst)
		{
			List<KillSummary> kills = byKey.computeIfAbsent(new HistoryKey(review.getMode(), review.getContract()), key -> new ArrayList<>());
			if (kills.size() < historySize)
			{
				kills.add(summarize(review));
			}
		}
		Map<HistoryKey, HistoryView> views = new LinkedHashMap<>();
		byKey.forEach((key, kills) -> views.put(key, view(key, kills)));
		return new HistoryIndex(Collections.unmodifiableMap(views));
	}

	static HistoryView view(HistoryKey key, List<KillSummary> kills)
	{
		List<Double> ticks = kills.stream()
			.filter(kill -> kill.getEndReason() == EndReason.YAMA_DIED && kill.getKillTicks() >= 0)
			.map(kill -> (double) kill.getKillTicks())
			.collect(Collectors.toList());
		List<Double> accuracy = kills.stream().map(KillSummary::getP3Accuracy).filter(value -> value != null).collect(Collectors.toList());
		List<Double> cost = kills.stream().map(KillSummary::getCost).filter(value -> value != null).map(Long::doubleValue).collect(Collectors.toList());
		List<Double> drained = kills.stream().map(KillSummary::getDefenceDrained).filter(value -> value != null).map(Integer::doubleValue).collect(Collectors.toList());
		return new HistoryView(
			key,
			List.copyOf(kills),
			mean(ticks),
			mean(accuracy),
			mean(cost),
			ticks.isEmpty() ? null : (int) Math.round(ticks.stream().mapToDouble(Double::doubleValue).min().getAsDouble()),
			accuracy.isEmpty() ? null : accuracy.stream().mapToDouble(Double::doubleValue).max().getAsDouble(),
			trend(ticks),
			trend(accuracy),
			trend(cost),
			trend(drained));
	}

	/** Values newest first: the mean of the first TREND_WINDOW against the mean of the next TREND_WINDOW. */
	static Trend trend(List<Double> newestFirst)
	{
		List<Double> last = newestFirst.subList(0, Math.min(TREND_WINDOW, newestFirst.size()));
		List<Double> previous = newestFirst.subList(last.size(), Math.min(2 * TREND_WINDOW, newestFirst.size()));
		return new Trend(mean(last), mean(previous));
	}

	static Double mean(List<Double> values)
	{
		OptionalDouble mean = values.stream().mapToDouble(Double::doubleValue).average();
		return mean.isPresent() ? mean.getAsDouble() : null;
	}
}
