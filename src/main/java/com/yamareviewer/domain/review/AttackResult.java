package com.yamareviewer.domain.review;

import lombok.Value;

@Value
public class AttackResult
{
	Attack attack;
	/** The prayer-check tick L of spec 6.5. */
	int checkTick;
	PrayerOutcome outcome;
	/** Ticks from the previous attack's check tick to the first tick the correct prayer was active; null when not computed. */
	Integer switchTicks;
}
