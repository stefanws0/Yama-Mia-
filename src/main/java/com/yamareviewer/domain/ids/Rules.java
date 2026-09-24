package com.yamareviewer.domain.ids;

import lombok.Builder;
import lombok.Value;

/** Tunables of the classification (spec 5.5). Not game IDs. */
@Value
@Builder(toBuilder = true)
public class Rules
{
	public static final Rules DEFAULT = Rules.builder()
		.prayerCheck(PrayerCheck.CAST)
		.prayerCheckOffset(0)
		.blockedMaxHit(3)
		.p1p2AttackCycle(8)
		.p3AttackCycle(7)
		.crashImpactWindow(1)
		.crashSetGap(6)
		.waveDisableWindow(5)
		.specResultWindow(6)
		.statRestoreTicks(100)
		.minAttackCountRatio(0.6)
		.minCycleGapRatio(0.8)
		.minAlternationRatio(0.9)
		.build();

	PrayerCheck prayerCheck;
	int prayerCheckOffset;
	int blockedMaxHit;
	int p1p2AttackCycle;
	int p3AttackCycle;
	int crashImpactWindow;
	int crashSetGap;
	int waveDisableWindow;
	int specResultWindow;
	int statRestoreTicks;
	double minAttackCountRatio;
	double minCycleGapRatio;
	double minAlternationRatio;
}
