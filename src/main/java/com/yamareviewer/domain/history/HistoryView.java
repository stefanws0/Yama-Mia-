package com.yamareviewer.domain.history;

import java.util.List;
import lombok.Value;

/** The last historySize kills of one mode and contract with their statistics (spec 7.3). */
@Value
public class HistoryView
{
	HistoryKey key;
	/** Newest first. */
	List<KillSummary> kills;
	Double averageKillTicks;
	Double averageP3Accuracy;
	Double averageCost;
	Integer fastestKillTicks;
	Double bestP3Accuracy;
	Trend killTicksTrend;
	Trend p3AccuracyTrend;
	Trend costTrend;
	Trend defenceDrainedTrend;
}
