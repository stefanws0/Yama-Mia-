package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** One row of the outcome table of spec 6.5; the first rule that returns an outcome wins. */
public interface OutcomeRule
{
	Optional<PrayerOutcome> apply(PrayerContext context);
}
