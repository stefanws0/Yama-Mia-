package com.yamareviewer.domain.drain;

import lombok.Value;

/**
 * Yama's modelled stats (spec 6.9). Drains refer to the normal-form limits even under a contract,
 * and Defence never goes below the floor.
 */
@Value
public class YamaStats
{
	public static final int NORMAL_DEFENCE = 225;
	public static final int NORMAL_MAGIC = 250;
	public static final int DEFENCE_FLOOR = 145;

	int defence;
	int magic;
	/** Drained by Eye of Ayak specs; not a stat, so it never restores. */
	int magicDefenceBonusDrained;

	public YamaStats withDefence(int value)
	{
		return new YamaStats(Math.max(DEFENCE_FLOOR, value), magic, magicDefenceBonusDrained);
	}

	public YamaStats withMagic(int value)
	{
		return new YamaStats(defence, value, magicDefenceBonusDrained);
	}

	public YamaStats plusBonusDrained(int amount)
	{
		return new YamaStats(defence, magic, magicDefenceBonusDrained + amount);
	}
}
