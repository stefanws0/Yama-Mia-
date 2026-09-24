package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 3: the correct prayer was active at L+1. */
public final class LateRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		return !context.correctAt(check) && context.correctAt(check + 1) ? Optional.of(PrayerOutcome.LATE) : Optional.empty();
	}
}
