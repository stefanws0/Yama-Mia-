package com.yamareviewer.domain.projection;

import java.util.List;

/**
 * The ordered projection list of spec 6.1. Part 3 inserts Glyphs, Attacks, Crashes, Waves before Flares;
 * Specs after Flares; PrayerReview, Opener, TickLog before DamageAttribution.
 */
public final class Projections
{
	private Projections()
	{
	}

	public static List<Projection<?>> standard()
	{
		return List.of(
			new PhasesProjection(),
			new ContractProjection(),
			new ModeProjection(),
			new FlaresProjection(),
			new DamageAttributionProjection(),
			new SuppliesProjection(),
			new DeathRecapProjection());
	}
}
