package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 2: the correct prayer was active at a tick in L-3 … L-1 but not at L. */
public final class TooEarlyRule implements OutcomeRule
{
	static final int LOOKBACK = 3;

	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		if (context.correctAt(check))
		{
			return Optional.empty();
		}
		for (int tick = check - LOOKBACK; tick < check; tick++)
		{
			if (context.correctAt(tick))
			{
				return Optional.of(PrayerOutcome.TOO_EARLY);
			}
		}
		return Optional.empty();
	}
}
