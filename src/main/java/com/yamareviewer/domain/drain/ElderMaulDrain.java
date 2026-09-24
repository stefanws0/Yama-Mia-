package com.yamareviewer.domain.drain;

/** Defence minus 35% of current Defence, rounded down. */
public final class ElderMaulDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.withDefence(stats.getDefence() - stats.getDefence() * 35 / 100);
	}
}
