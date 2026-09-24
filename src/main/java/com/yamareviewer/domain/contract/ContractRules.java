package com.yamareviewer.domain.contract;

import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import lombok.Builder;
import lombok.Value;

/** The per-contract values of spec 6.3 and 6.3.1, as data. Every contract shares the common rules. */
@Value
@Builder(toBuilder = true)
public class ContractRules
{
	/** P1/P2 standard attacks come every 7 ticks under any contract (spec 6.3). */
	public static final int CONTRACT_P1P2_ATTACK_CYCLE = 7;

	int yamaHp;
	int baseDefence;
	int baseMagic;
	int p1p2AttackCycle;
	/** Null when the contract changes prayer penetration; BlockedDamage and GraphicVsBehaviour are then skipped. */
	Integer blockedMaxHit;
	boolean soloOnly;
	boolean randomAttackStyles;
	boolean crashInEveryPhase;
	int p3CrashSets;
	boolean consumablesHaveNoEffect;
	boolean specsAlwaysHit;
	boolean runEnergyDeath;
	boolean shadowPools;
	/** Ticks after which the game switches protection prayers off (Divine Severance: 2), 0 = never. */
	int prayerAutoOffTicks;

	/** The values of spec 6.3 / 6.3.1. NONE: 2500 HP, Defence 225, Magic 250, the tunable cycle and blockedMaxHit, 3 P3 crash sets. */
	public static ContractRules of(Contract contract, Rules rules)
	{
		ContractRulesBuilder common = contract.isContract() ? contractCommon(rules) : normal(rules);
		switch (contract)
		{
			case FORFEIT_BREATH:
				return common.soloOnly(true).runEnergyDeath(true).build();
			case GLYPHIC_ATTENUATION:
				return common.soloOnly(true).yamaHp(2875).build();
			case SENSORY_CLOUDING:
				return common.soloOnly(true).blockedMaxHit(null).build();
			case DIVINE_SEVERANCE:
				return common.soloOnly(true).blockedMaxHit(0).prayerAutoOffTicks(2).build();
			case BLOODIED_BLOWS:
				return common.soloOnly(true).yamaHp(3000).blockedMaxHit(null).build();
			case FAMILIAR_ACQUISITION:
				return common.blockedMaxHit(null).consumablesHaveNoEffect(true).specsAlwaysHit(true).build();
			case CATALYST_ACQUISITION:
			case WORM_ACQUISITION:
				return acquisition(common).build();
			case SHARD_ACQUISITION:
				return acquisition(common).randomAttackStyles(true).build();
			case OATHPLATE_ACQUISITION:
			case HARMONY_ACQUISITION:
				return acquisition(common).soloOnly(true).shadowPools(true).build();
			default:
				return common.build();
		}
	}

	private static ContractRulesBuilder normal(Rules rules)
	{
		return ContractRules.builder()
			.yamaHp(2500)
			.baseDefence(225)
			.baseMagic(250)
			.p1p2AttackCycle(rules.getP1p2AttackCycle())
			.blockedMaxHit(rules.getBlockedMaxHit())
			.soloOnly(false)
			.randomAttackStyles(false)
			.crashInEveryPhase(false)
			.p3CrashSets(3)
			.consumablesHaveNoEffect(false)
			.specsAlwaysHit(false)
			.runEnergyDeath(false)
			.shadowPools(false)
			.prayerAutoOffTicks(0);
	}

	/** Spec 6.3 "common rules under any contract": Defence 247, Magic 275, the 7-tick cycle. */
	private static ContractRulesBuilder contractCommon(Rules rules)
	{
		return normal(rules).baseDefence(247).baseMagic(275).p1p2AttackCycle(CONTRACT_P1P2_ATTACK_CYCLE);
	}

	/** Catalyst, Worm, Shard, Oathplate and Harmony: Shadow Crash in every phase, five P3 sets. */
	private static ContractRulesBuilder acquisition(ContractRulesBuilder common)
	{
		return common.crashInEveryPhase(true).p3CrashSets(5);
	}
}
