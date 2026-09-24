package com.yamareviewer.domain.drain;

/** What one landed special attack does to Yama's stats (spec 6.9). */
public interface DrainRule
{
	/** The stats after a landed spec that dealt {@code damage}. */
	YamaStats apply(YamaStats stats, int damage);
}
