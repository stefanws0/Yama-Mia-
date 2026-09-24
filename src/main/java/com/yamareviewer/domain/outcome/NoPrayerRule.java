package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 5: otherwise. */
public final class NoPrayerRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		return Optional.of(PrayerOutcome.NO_PRAYER);
	}
}
