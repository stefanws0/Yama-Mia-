package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Rules;

/**
 * Applies landed specs in tick order to Yama's modelled stats (spec 6.9). Every {@code statRestoreTicks}
 * ticks after fight start, drained Defence and Magic each restore one level towards the fight's base.
 * Not thread-safe; one instance per review.
 */
public final class DrainModel
{
	private final YamaStats base;
	private final int restoreTicks;
	private YamaStats stats;
	private int restoredUpTo;

	public DrainModel(YamaStats base, int statRestoreTicks)
	{
		this.base = base;
		this.restoreTicks = Math.max(1, statRestoreTicks);
		this.stats = base;
	}

	/** Base 225/250, or 247/275 under a contract, from the contract rules. */
	public static DrainModel forFight(ContractRules contract, Rules rules)
	{
		return new DrainModel(new YamaStats(contract.getBaseDefence(), contract.getBaseMagic(), 0), rules.getStatRestoreTicks());
	}

	public YamaStats base()
	{
		return base;
	}

	public YamaStats stats()
	{
		return stats;
	}

	/** Applies every restore step due up to and including {@code tick}; ticks already passed are ignored. */
	public YamaStats advanceTo(int tick)
	{
		int due = tick / restoreTicks;
		int done = restoredUpTo / restoreTicks;
		for (int step = done; step < due; step++)
		{
			restoreOneLevel();
		}
		restoredUpTo = Math.max(restoredUpTo, tick);
		return stats;
	}

	/** Restores up to {@code tick}, then applies the rule for a landed spec that dealt {@code damage}. */
	public YamaStats apply(int tick, DrainRule rule, int damage)
	{
		advanceTo(tick);
		stats = rule.apply(stats, damage);
		return stats;
	}

	private void restoreOneLevel()
	{
		int defence = stats.getDefence() < base.getDefence() ? stats.getDefence() + 1 : stats.getDefence();
		int magic = stats.getMagic() < base.getMagic() ? stats.getMagic() + 1 : stats.getMagic();
		stats = new YamaStats(defence, magic, stats.getMagicDefenceBonusDrained());
	}
}
