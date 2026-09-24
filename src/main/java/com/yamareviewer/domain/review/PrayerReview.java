package com.yamareviewer.domain.review;

import java.util.List;
import java.util.Optional;
import lombok.Value;

/** The P3 prayer review: every P3 attack aimed at you with its outcome (spec 6.5). */
@Value
public class PrayerReview
{
	List<AttackResult> results;
	/** P3 attacks aimed at you whose style could not be determined; they are not scored. */
	int unscored;
	/** "lost alternation", or "wrong prayer" under Shard Acquisition. */
	String lostAlternationLabel;
	/** Contract notes shown above the timeline, for example the Divine Severance auto-off note. */
	List<String> notes;

	public int count(PrayerOutcome outcome)
	{
		int count = 0;
		for (AttackResult result : results)
		{
			if (result.getOutcome() == outcome)
			{
				count++;
			}
		}
		return count;
	}

	/** Attacks that got an outcome other than PRAYER_DISABLED. */
	public int scored()
	{
		return results.size() - count(PrayerOutcome.PRAYER_DISABLED);
	}

	public int blocked()
	{
		return count(PrayerOutcome.BLOCKED);
	}

	public int mistakes()
	{
		int mistakes = 0;
		for (AttackResult result : results)
		{
			if (result.getOutcome().isMistake())
			{
				mistakes++;
			}
		}
		return mistakes;
	}

	/** Blocked out of scored; empty when nothing was scored. */
	public Optional<Double> accuracy()
	{
		return scored() == 0 ? Optional.empty() : Optional.of((double) blocked() / scored());
	}

	public String label(PrayerOutcome outcome)
	{
		switch (outcome)
		{
			case PRAYER_DISABLED:
				return "prayers disabled";
			case BLOCKED:
				return "blocked";
			case TOO_EARLY:
				return "too early";
			case LATE:
				return "late";
			case LOST_ALTERNATION:
				return lostAlternationLabel;
			default:
				return "no prayer";
		}
	}
}
