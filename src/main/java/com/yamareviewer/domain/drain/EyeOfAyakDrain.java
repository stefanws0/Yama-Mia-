package com.yamareviewer.domain.drain;

/** Adds the damage dealt to the magic defence bonus drained; not a stat, never restores. */
public final class EyeOfAyakDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.plusBonusDrained(Math.max(0, damage));
	}
}
