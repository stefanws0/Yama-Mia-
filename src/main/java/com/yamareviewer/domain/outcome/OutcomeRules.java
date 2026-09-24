package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.List;
import java.util.Optional;

/** The ordered rules of spec 6.5; adding an outcome means adding a class to this list. */
public final class OutcomeRules
{
	private static final List<OutcomeRule> STANDARD = List.of(
		new PrayerDisabledRule(), new BlockedRule(), new TooEarlyRule(), new LateRule(), new LostAlternationRule(), new NoPrayerRule());

	private OutcomeRules()
	{
	}

	public static List<OutcomeRule> standard()
	{
		return STANDARD;
	}

	/** The first matching rule's outcome; NoPrayerRule always matches. */
	public static PrayerOutcome evaluate(PrayerContext context)
	{
		for (OutcomeRule rule : STANDARD)
		{
			Optional<PrayerOutcome> outcome = rule.apply(context);
			if (outcome.isPresent())
			{
				return outcome.get();
			}
		}
		return PrayerOutcome.NO_PRAYER;
	}
}
