package com.yamareviewer.domain.health;

import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.review.Attack;

/**
 * The prayer-check tick L of spec 6.5: the cast tick, or under HITSPLAT the landing tick (the cast tick when
 * nothing landed), plus the offset. Mirrors Part 3's package-private PrayerReviewProjection.checkTick, so the
 * checks judge the tick the prayer review scored; keep the two in step.
 */
public final class PrayerCheckTick
{
	private PrayerCheckTick()
	{
	}

	public static int of(Attack attack, Rules rules)
	{
		int base = rules.getPrayerCheck() == PrayerCheck.HITSPLAT && attack.getLandingTick() != null
			? attack.getLandingTick()
			: attack.getCastTick();
		return base + rules.getPrayerCheckOffset();
	}
}
