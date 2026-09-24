package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.review.PrayerOutcome;
import java.util.Optional;

/** Rule 0: a PRAYER_DISABLED_MESSAGE within waveDisableWindow ticks before L, and no protection prayer active since. */
public final class PrayerDisabledRule implements OutcomeRule
{
	@Override
	public Optional<PrayerOutcome> apply(PrayerContext context)
	{
		int check = context.getCheckTick();
		for (int message : context.getPrayerDisabledTicks())
		{
			if (message > check || message < check - context.getWaveDisableWindow())
			{
				continue;
			}
			boolean noneSince = true;
			for (int tick = message; tick <= check; tick++)
			{
				if (!context.prayersAt(tick).isEmpty())
				{
					noneSince = false;
					break;
				}
			}
			if (noneSince)
			{
				return Optional.of(PrayerOutcome.PRAYER_DISABLED);
			}
		}
		return Optional.empty();
	}
}
