package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ContractProjectionTest
{
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);
	private static final int STANDARD_ATTACK = TestIds.id(Role.YAMA_STANDARD_ATTACK);
	private static final int JUDGE_ID = TestIds.id(Role.JUDGE);
	private static final int SHARD_ITEM = TestIds.id(Role.CONTRACT_ITEM_SHARD);

	private static Contract project(KillLog kill)
	{
		return project(kill, TestIds.registry());
	}

	private static Contract project(KillLog kill, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new PhasesProjection(), new ContractProjection()), ids, Rules.DEFAULT);
		return builder.run(kill, ReviewSettings.DEFAULT).value(Sections.CONTRACT).get();
	}

	/** Standard attacks every {@code cycle} ticks from tick 5 through a 60-tick P1, then the Judge. */
	private static KillLogBuilder p1WithAttacksEvery(int cycle)
	{
		KillLogBuilder kill = KillLogBuilder.kill();
		for (int tick = 5; tick < 60; tick += cycle)
		{
			kill.ticks(tick - kill.tick()).yamaAnimates(STANDARD_ATTACK);
		}
		return kill.ticks(60 - kill.tick()).npcSpawns(Actor.JUDGE, JUDGE_ID).ticks(10);
	}

	@Test
	public void theContractPlateWins()
	{
		KillLog kill = KillLogBuilder.kill()
			.inventory(SHARD_ITEM, "Shard contract", -1)
			.ticks(3).widgetText(WIDGET, "Contract of Bloodied Blows")
			.ticks(5).end(EndReason.LEFT);

		assertEquals(Contract.BLOODIED_BLOWS, project(kill));
	}

	@Test
	public void aContractItemLeavingTheInventoryEarlyIsTheContract()
	{
		KillLog kill = KillLogBuilder.kill().inventory(SHARD_ITEM, "Shard contract", -1).ticks(5).end(EndReason.LEFT);

		assertEquals(Contract.SHARD_ACQUISITION, project(kill));
	}

	@Test
	public void aContractItemLeavingLateOrArrivingIsNoCue()
	{
		assertEquals(Contract.NONE, project(KillLogBuilder.kill().ticks(10).inventory(SHARD_ITEM, "Shard contract", -1).ticks(5).end(EndReason.LEFT)));
		assertEquals(Contract.NONE, project(KillLogBuilder.kill().inventory(SHARD_ITEM, "Shard contract", 1).ticks(5).end(EndReason.LEFT)));
		assertEquals(Contract.NONE, project(KillLogBuilder.kill().widgetText(WIDGET + 1, "Contract of Bloodied Blows").ticks(5).end(EndReason.LEFT)));
	}

	@Test
	public void aSevenTickP1CycleMeansAnUnknownContract()
	{
		assertEquals(Contract.UNKNOWN_CONTRACT, project(p1WithAttacksEvery(7).end(EndReason.LEFT)));
		assertEquals(Contract.NONE, project(p1WithAttacksEvery(8).end(EndReason.LEFT)));
	}

	@Test
	public void theCycleIsNotUsedWhenPhasesAreUnknown()
	{
		assertEquals(Contract.NONE, project(p1WithAttacksEvery(7).end(EndReason.LEFT), TestIds.registryWithout(Role.JUDGE)));
		assertEquals(Contract.NONE, project(p1WithAttacksEvery(7).end(EndReason.LEFT), TestIds.registryWithout(Role.YAMA_STANDARD_ATTACK)));
	}

	@Test
	public void fewGapsAreNotEnough()
	{
		KillLog kill = KillLogBuilder.kill()
			.ticks(5).yamaAnimates(STANDARD_ATTACK).ticks(7).yamaAnimates(STANDARD_ATTACK).ticks(7).yamaAnimates(STANDARD_ATTACK)
			.ticks(41).npcSpawns(Actor.JUDGE, JUDGE_ID).ticks(10).end(EndReason.LEFT);

		assertEquals(Contract.NONE, project(kill));
	}

	@Test
	public void keyAndRoles()
	{
		ContractProjection projection = new ContractProjection();

		assertEquals(Sections.CONTRACT, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
