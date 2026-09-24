package com.yamareviewer.domain.drain;

/** Defence minus the damage dealt. */
public final class BandosGodswordDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.withDefence(stats.getDefence() - Math.max(0, damage));
	}
}
