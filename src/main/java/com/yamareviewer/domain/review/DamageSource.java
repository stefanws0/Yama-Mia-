package com.yamareviewer.domain.review;

/** Where a damage hitsplat on a player came from (spec 6.10). */
public enum DamageSource
{
	STANDARD("Standard attacks", false),
	MELEE("Melee", false),
	MELEE_SPLASH("Melee splash", true),
	SHADOW_CRASH("Shadow Crash", false),
	SHADOW_WAVE("Shadow Waves", false),
	FLARE("Flare explosions", true),
	FIRE_STREAK("Fire Streaks", false),
	SPECIAL("Specials", true),
	SHADOW_POOL("Shadow pools", false),
	JUDGE("Judge", false),
	OTHER("Other", false);

	private final String label;
	private final boolean shared;

	DamageSource(String label, boolean shared)
	{
		this.label = label;
		this.shared = shared;
	}

	public String label()
	{
		return label;
	}

	/** MELEE_SPLASH, SPECIAL and FLARE: totalled as shared mechanics (spec 6.10). */
	public boolean shared()
	{
		return shared;
	}
}
