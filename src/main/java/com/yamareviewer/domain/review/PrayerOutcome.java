package com.yamareviewer.domain.review;

/** The outcome of one P3 attack aimed at you, in the order the rules of spec 6.5 are tried. */
public enum PrayerOutcome
{
	PRAYER_DISABLED,
	BLOCKED,
	TOO_EARLY,
	LATE,
	LOST_ALTERNATION,
	NO_PRAYER;

	public boolean isMistake()
	{
		return this != PRAYER_DISABLED && this != BLOCKED;
	}
}
