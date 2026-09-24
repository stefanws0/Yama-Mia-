package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 1: the correct prayer was active at L. */
public final class BlockedRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		return context.correctAt(context.getCheckTick()) ? Optional.of(PrayerOutcome.BLOCKED) : Optional.empty();
	}
}
