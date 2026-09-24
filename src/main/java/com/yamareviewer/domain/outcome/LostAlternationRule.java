package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 4: another protection prayer, Protect from Melee included, was active at L. */
public final class LostAlternationRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		return !context.correctAt(check) && !context.prayersAt(check).isEmpty()
			? Optional.of(PrayerOutcome.LOST_ALTERNATION)
			: Optional.empty();
	}
}
