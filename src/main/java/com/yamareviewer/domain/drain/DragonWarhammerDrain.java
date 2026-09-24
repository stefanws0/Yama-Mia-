package com.yamareviewer.domain.drain;

/** Defence minus 30% of current Defence, rounded down, only when the hit dealt damage. */
public final class DragonWarhammerDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		if (damage <= 0)
		{
			return stats;
		}
		return stats.withDefence(stats.getDefence() - stats.getDefence() * 30 / 100);
	}
}
