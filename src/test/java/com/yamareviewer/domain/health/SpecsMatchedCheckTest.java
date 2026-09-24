package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecsMatchedCheckTest
{
	private static final int EMBERLIGHT = TestIds.id(Role.WEAPON_EMBERLIGHT);

	private final SpecsMatchedCheck check = new SpecsMatchedCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	@Test
	public void matchedSpecsPass()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void anEnergyDropWithoutAnimationFails()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		kill.weapon(EMBERLIGHT).spec(100).endTick().spec(75).ticks(CheckFights.CYCLE - 1);
		CheckFights.blockedAttacks(kill, 3);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("WEAPON_EMBERLIGHT"));
		assertTrue(result.detail(), result.detail().contains("25%"));
		assertEquals(OptionalInt.of(CheckFights.P3_START + 3 * CheckFights.CYCLE + 1), result.tick());
	}

	@Test
	public void otherWeaponSpecsAreNotChecked()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		kill.weapon(4151).spec(100).endTick().spec(75).ticks(CheckFights.CYCLE - 1);
		CheckFights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void theAnimationMayComeOneTickEarlier()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		kill.weapon(EMBERLIGHT).spec(100).animates(Actor.SELF, TestIds.id(Role.SPEC_EMBERLIGHT)).endTick().spec(75).ticks(CheckFights.CYCLE - 1);
		CheckFights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void noWeaponIsNotChecked()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		kill.weapon(-1).spec(100).endTick().spec(75).ticks(CheckFights.CYCLE - 1);
		CheckFights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void skippedWhenSpecsAreNotAvailable()
	{
		assertTrue(check.check(CheckFights.solo().build(), TestContext.empty()).skipped());
	}

	@Test
	public void hidesSpecsAndNamesEverySpecRole()
	{
		assertEquals("SpecsMatched", check.name());
		assertEquals(List.of(Sections.SPECS), check.hides());
		assertEquals(SpecEnergyDrops.SPEC_ROLES, check.reportRoles());
		for (Contract contract : Contract.values())
		{
			assertTrue(contract.name(), check.applies(ContractRules.of(contract, Rules.DEFAULT)));
		}
	}
}
