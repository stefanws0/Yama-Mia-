package com.yamareviewer.domain.contract;

import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ContractRulesTest
{
	private static ContractRules of(Contract contract)
	{
		return ContractRules.of(contract, Rules.DEFAULT);
	}

	@Test
	public void noContractUsesTheNormalFormValues()
	{
		ContractRules rules = of(Contract.NONE);

		assertEquals(2500, rules.getYamaHp());
		assertEquals(225, rules.getBaseDefence());
		assertEquals(250, rules.getBaseMagic());
		assertEquals(8, rules.getP1p2AttackCycle());
		assertEquals(Integer.valueOf(3), rules.getBlockedMaxHit());
		assertEquals(3, rules.getP3CrashSets());
		assertEquals(0, rules.getPrayerAutoOffTicks());
		assertFalse(rules.isSoloOnly());
		assertFalse(rules.isRandomAttackStyles());
		assertFalse(rules.isCrashInEveryPhase());
		assertFalse(rules.isConsumablesHaveNoEffect());
		assertFalse(rules.isSpecsAlwaysHit());
		assertFalse(rules.isRunEnergyDeath());
		assertFalse(rules.isShadowPools());
	}

	@Test
	public void everyContractSharesTheCommonRules()
	{
		for (Contract contract : Contract.values())
		{
			if (!contract.isContract())
			{
				continue;
			}
			ContractRules rules = of(contract);
			assertEquals(contract.name(), 247, rules.getBaseDefence());
			assertEquals(contract.name(), 275, rules.getBaseMagic());
			assertEquals(contract.name(), 7, rules.getP1p2AttackCycle());
		}
	}

	@Test
	public void theUnknownContractHasOnlyTheCommonRules()
	{
		ContractRules rules = of(Contract.UNKNOWN_CONTRACT);

		assertEquals(2500, rules.getYamaHp());
		assertEquals(Integer.valueOf(3), rules.getBlockedMaxHit());
		assertEquals(3, rules.getP3CrashSets());
		assertFalse(rules.isSoloOnly());
		assertFalse(rules.isCrashInEveryPhase());
	}

	@Test
	public void theContractTableOfSpec631()
	{
		assertTrue(of(Contract.FORFEIT_BREATH).isSoloOnly());
		assertTrue(of(Contract.FORFEIT_BREATH).isRunEnergyDeath());

		assertEquals(2875, of(Contract.GLYPHIC_ATTENUATION).getYamaHp());
		assertTrue(of(Contract.GLYPHIC_ATTENUATION).isSoloOnly());

		assertNull(of(Contract.SENSORY_CLOUDING).getBlockedMaxHit());
		assertTrue(of(Contract.SENSORY_CLOUDING).isSoloOnly());

		assertEquals(Integer.valueOf(0), of(Contract.DIVINE_SEVERANCE).getBlockedMaxHit());
		assertEquals(2, of(Contract.DIVINE_SEVERANCE).getPrayerAutoOffTicks());
		assertTrue(of(Contract.DIVINE_SEVERANCE).isSoloOnly());

		assertEquals(3000, of(Contract.BLOODIED_BLOWS).getYamaHp());
		assertNull(of(Contract.BLOODIED_BLOWS).getBlockedMaxHit());
		assertTrue(of(Contract.BLOODIED_BLOWS).isSoloOnly());

		assertFalse(of(Contract.FAMILIAR_ACQUISITION).isSoloOnly());
		assertNull(of(Contract.FAMILIAR_ACQUISITION).getBlockedMaxHit());
		assertTrue(of(Contract.FAMILIAR_ACQUISITION).isConsumablesHaveNoEffect());
		assertTrue(of(Contract.FAMILIAR_ACQUISITION).isSpecsAlwaysHit());

		for (Contract acquisition : new Contract[]{Contract.CATALYST_ACQUISITION, Contract.WORM_ACQUISITION, Contract.SHARD_ACQUISITION,
			Contract.OATHPLATE_ACQUISITION, Contract.HARMONY_ACQUISITION})
		{
			assertTrue(acquisition.name(), of(acquisition).isCrashInEveryPhase());
			assertEquals(acquisition.name(), 5, of(acquisition).getP3CrashSets());
			assertEquals(acquisition.name(), Integer.valueOf(3), of(acquisition).getBlockedMaxHit());
		}
		assertFalse(of(Contract.CATALYST_ACQUISITION).isSoloOnly());
		assertFalse(of(Contract.WORM_ACQUISITION).isSoloOnly());
		assertFalse(of(Contract.SHARD_ACQUISITION).isSoloOnly());
		assertTrue(of(Contract.SHARD_ACQUISITION).isRandomAttackStyles());
		assertFalse(of(Contract.CATALYST_ACQUISITION).isRandomAttackStyles());
		assertTrue(of(Contract.OATHPLATE_ACQUISITION).isSoloOnly());
		assertTrue(of(Contract.OATHPLATE_ACQUISITION).isShadowPools());
		assertTrue(of(Contract.HARMONY_ACQUISITION).isShadowPools());
		assertFalse(of(Contract.SHARD_ACQUISITION).isShadowPools());
	}

	@Test
	public void tunablesFlowIntoTheRules()
	{
		Rules tuned = Rules.DEFAULT.toBuilder().blockedMaxHit(5).p1p2AttackCycle(9).build();

		assertEquals(Integer.valueOf(5), ContractRules.of(Contract.NONE, tuned).getBlockedMaxHit());
		assertEquals(9, ContractRules.of(Contract.NONE, tuned).getP1p2AttackCycle());
		assertEquals(Integer.valueOf(5), ContractRules.of(Contract.CATALYST_ACQUISITION, tuned).getBlockedMaxHit());
		assertEquals(7, ContractRules.of(Contract.CATALYST_ACQUISITION, tuned).getP1p2AttackCycle());
	}
}
