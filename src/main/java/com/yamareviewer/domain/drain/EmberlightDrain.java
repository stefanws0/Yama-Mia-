package com.yamareviewer.domain.drain;

/** Defence minus 34 (15% of the normal-form Defence plus one); stacks down to the floor. */
public final class EmberlightDrain implements DrainRule
{
	static final int DRAIN = 34;

	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats.withDefence(stats.getDefence() - DRAIN);
	}
}
