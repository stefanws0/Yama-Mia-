package com.yamareviewer.domain.model;

import com.yamareviewer.domain.ids.Role;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ContractTest
{
	@Test
	public void widgetTextMatchesTheShortNameIgnoringCase()
	{
		assertEquals(Optional.of(Contract.BLOODIED_BLOWS), Contract.fromWidgetText("Contract of Bloodied Blows"));
		assertEquals(Optional.of(Contract.FORFEIT_BREATH), Contract.fromWidgetText("CONTRACT OF FORFEIT BREATH"));
		assertEquals(Optional.of(Contract.HARMONY_ACQUISITION), Contract.fromWidgetText("harmony acquisition"));
		assertEquals(Optional.empty(), Contract.fromWidgetText("Yama"));
		assertEquals(Optional.empty(), Contract.fromWidgetText(""));
		assertEquals(Optional.empty(), Contract.fromWidgetText(null));
	}

	@Test
	public void everyRealContractHasAShortNameAndAnItemRole()
	{
		for (Contract contract : Contract.values())
		{
			if (contract == Contract.NONE || contract == Contract.UNKNOWN_CONTRACT)
			{
				assertNull(contract.shortName());
				assertNull(contract.itemRole());
			}
			else
			{
				assertTrue(contract.name(), Role.CONTRACT_ITEMS.contains(contract.itemRole()));
				assertEquals(Optional.of(contract), Contract.fromWidgetText("Contract of " + contract.shortName()));
			}
		}
	}

	@Test
	public void onlyNoneIsNotAContract()
	{
		assertFalse(Contract.NONE.isContract());
		assertTrue(Contract.UNKNOWN_CONTRACT.isContract());
		assertTrue(Contract.SHARD_ACQUISITION.isContract());
	}

	@Test
	public void displayNames()
	{
		assertEquals("No contract", Contract.NONE.displayName());
		assertEquals("Unknown contract", Contract.UNKNOWN_CONTRACT.displayName());
		assertEquals("Contract of Divine Severance", Contract.DIVINE_SEVERANCE.displayName());
	}

	@Test
	public void labelsForChatAndPanel()
	{
		assertEquals("duo host", Mode.DUO_HOST.label());
		assertEquals("Judge 1", Phase.JUDGE_1.label());
		assertTrue(Phase.JUDGE_2.isJudge());
		assertFalse(Phase.P3.isJudge());
	}
}
