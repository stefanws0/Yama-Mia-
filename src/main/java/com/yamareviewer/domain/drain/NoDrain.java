package com.yamareviewer.domain.drain;

/** Soulflame horn, purging staff and Saradomin godsword drain nothing. */
public final class NoDrain implements DrainRule
{
	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		return stats;
	}
}
