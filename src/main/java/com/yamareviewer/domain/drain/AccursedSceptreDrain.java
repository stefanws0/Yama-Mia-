package com.yamareviewer.domain.drain;

/**
 * Defence -34 down to 191 and Magic -38 down to 212: the limits are 15% below the normal-form stats,
 * so the drain does not stack in a normal fight and drains twice under a contract.
 */
public final class AccursedSceptreDrain implements DrainRule
{
	static final int DEFENCE_LIMIT = 191;
	static final int MAGIC_LIMIT = 212;
	static final int DEFENCE_DRAIN = 34;
	static final int MAGIC_DRAIN = 38;

	@Override
	public YamaStats apply(YamaStats stats, int damage)
	{
		YamaStats result = stats;
		if (result.getDefence() > DEFENCE_LIMIT)
		{
			result = result.withDefence(Math.max(DEFENCE_LIMIT, result.getDefence() - DEFENCE_DRAIN));
		}
		if (result.getMagic() > MAGIC_LIMIT)
		{
			result = result.withMagic(Math.max(MAGIC_LIMIT, result.getMagic() - MAGIC_DRAIN));
		}
		return result;
	}
}
