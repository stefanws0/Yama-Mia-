package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.history.HistoryView;
import com.yamareviewer.domain.history.KillSummary;
import com.yamareviewer.domain.history.Trend;
import com.yamareviewer.domain.review.ReviewStatus;
import java.util.List;

/** Renders a HistoryView to strings, so the panel does no arithmetic (spec 7.3). */
public final class HistoryFormatter
{
	private HistoryFormatter()
	{
	}

	public static List<String> summaryLines(HistoryView view)
	{
		int kills = view.getKills().size();
		if (kills == 0)
		{
			return List.of("No kills yet");
		}
		String time = view.getAverageKillTicks() == null ? ChatLines.NOT_AVAILABLE
			: "avg " + Formats.duration((int) Math.round(view.getAverageKillTicks())) + ", best " + Formats.duration(view.getFastestKillTicks())
				+ arrow(view.getKillTicksTrend(), true);
		String accuracy = view.getAverageP3Accuracy() == null ? ChatLines.NOT_AVAILABLE
			: "avg " + Formats.percent(view.getAverageP3Accuracy()) + ", best " + Formats.percent(view.getBestP3Accuracy())
				+ arrow(view.getP3AccuracyTrend(), false);
		String cost = view.getAverageCost() == null ? ChatLines.NOT_AVAILABLE
			: "avg " + Formats.compactGp(Math.round(view.getAverageCost())) + arrow(view.getCostTrend(), true);
		Trend drained = view.getDefenceDrainedTrend();
		String defence = drained.getLast10Mean() == null ? ChatLines.NOT_AVAILABLE
			: "recent avg " + Math.round(drained.getLast10Mean()) + arrow(drained, false);
		return List.of(
			kills + (kills == 1 ? " kill" : " kills"),
			"Kill time: " + time,
			"P3 accuracy: " + accuracy,
			"Cost per kill: " + cost,
			"Defence drained: " + defence);
	}

	/** "2026-09-24 21:03 · 4:12 · complete · 318k" */
	public static String killLine(KillSummary kill)
	{
		String time = kill.getKillTicks() < 0 ? ChatLines.NOT_AVAILABLE : Formats.duration(kill.getKillTicks());
		String outcome;
		if (kill.getEndReason() == EndReason.PLAYER_DIED)
		{
			outcome = "died";
		}
		else if (kill.getEndReason() == EndReason.LEFT)
		{
			outcome = "left";
		}
		else
		{
			outcome = kill.getStatus() == ReviewStatus.COMPLETE ? "complete" : "incomplete";
		}
		String cost = kill.getCost() == null ? "" : " · " + Formats.compactGp(kill.getCost());
		return Formats.date(kill.getStartEpochMs()) + " · " + time + " · " + outcome + cost;
	}

	private static String arrow(Trend trend, boolean lowerIsBetter)
	{
		String text = Formats.trend(trend, lowerIsBetter);
		return text.isEmpty() ? "" : " " + text;
	}
}
