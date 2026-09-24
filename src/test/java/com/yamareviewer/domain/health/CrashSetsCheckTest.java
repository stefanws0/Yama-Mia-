package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CrashSetsCheckTest
{
	private final CrashSetsCheck check = new CrashSetsCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	/** Two lines two ticks apart, then idle: an incomplete set that takes one attack cycle. */
	private static KillLogBuilder twoLineSet(KillLogBuilder kill, int x, int y)
	{
		CheckFights.crashLine(kill, x, y).ticks(2);
		CheckFights.crashLine(kill, x, y).ticks(CheckFights.CYCLE - 2);
		return kill;
	}

	@Test
	public void completeSetsPass()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void noCrashLinesIsNoEvidence()
	{
		KillLog log = CheckFights.finish(CheckFights.blockedAttacks(CheckFights.soloUntilP3(), 8), EndReason.YAMA_DIED);

		assertEquals(CheckResult.pass(), run(log));
	}

	@Test
	public void anIncompleteSetFails()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		twoLineSet(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
		CheckFights.blockedAttacks(kill, 3);
		CheckFights.crashSet(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
		CheckFights.blockedAttacks(kill, 3);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("2 lines for SELF"));
		assertTrue(result.detail(), result.detail().contains("expects 3 sets in P3"));
		assertEquals(OptionalInt.of(CheckFights.P3_START + 3 * CheckFights.CYCLE), result.tick());
	}

	@Test
	public void theLastSetMayBeIncomplete()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		CheckFights.crashSet(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
		CheckFights.blockedAttacks(kill, 3);
		twoLineSet(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
		CheckFights.blockedAttacks(kill, 3);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void aSetWithTooManyLinesFails()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		for (int line = 0; line < 4; line++)
		{
			CheckFights.crashLine(kill, CheckFights.SELF_X, CheckFights.SELF_Y).ticks(2);
		}
		kill.ticks(CheckFights.CYCLE - 1);
		CheckFights.blockedAttacks(kill, 3);
		CheckFights.crashSet(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
		CheckFights.blockedAttacks(kill, 3);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("4 lines for SELF"));
	}

	@Test
	public void linesAreCountedPerPlayer()
	{
		// Relies on Part 3 giving each line to the player nearest its centre fireball (spec 6.6).
		KillLogBuilder kill = CheckFights.duoUntilP3();
		CheckFights.blockedAttacks(kill, 3);
		for (int line = 0; line < 3; line++)
		{
			CheckFights.crashLine(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
			CheckFights.crashLine(kill, CheckFights.PARTNER_X, CheckFights.PARTNER_Y).ticks(2);
		}
		kill.ticks(1);
		CheckFights.blockedAttacks(kill, 3);
		for (int line = 0; line < 3; line++)
		{
			CheckFights.crashLine(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
			if (line < 2)
			{
				CheckFights.crashLine(kill, CheckFights.PARTNER_X, CheckFights.PARTNER_Y);
			}
			kill.ticks(2);
		}
		kill.ticks(1);
		CheckFights.blockedAttacks(kill, 3);
		CheckFights.crashSet(kill, CheckFights.SELF_X, CheckFights.SELF_Y);
		CheckFights.blockedAttacks(kill, 3);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("2 lines for PARTNER"));
	}

	@Test
	public void skippedWhenCrashesAreNotAvailableAndAppliesUnderEveryContract()
	{
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

		assertTrue(check.check(CheckFights.solo().build(), context).skipped());
		for (Contract contract : Contract.values())
		{
			assertTrue(contract.name(), check.applies(ContractRules.of(contract, Rules.DEFAULT)));
		}
		assertEquals("CrashSets", check.name());
		assertEquals(List.of(Sections.CRASHES), check.hides());
		assertEquals(Set.of(Role.CRASH_FIREBALL, Role.CRASH_IMPACT), check.reportRoles());
	}
}
