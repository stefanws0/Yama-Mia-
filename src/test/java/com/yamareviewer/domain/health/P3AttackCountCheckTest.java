package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class P3AttackCountCheckTest
{
	private final P3AttackCountCheck check = new P3AttackCountCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	@Test
	public void aHealthyKillPasses()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void tooFewAttacksFail()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 3).ticks(150);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("3 standard attacks in 171 P3 ticks"));
		assertEquals(OptionalInt.of(CheckFights.P3_START), result.tick());
	}

	@Test
	public void irregularGapsFail()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = CheckFights.alternating(i);
			CheckFights.attack(kill, style, Actor.SELF, 2, CheckFights.correct(style)).ticks(3);
		}

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("0 of 11 gaps"));
		assertEquals(OptionalInt.of(CheckFights.P3_START + 10), result.tick());
	}

	@Test
	public void noP3MeansNoEvidence()
	{
		KillLogBuilder kill = CheckFights.solo().ticks(40);
		CheckFights.judgePhase(kill).ticks(10);

		CheckResult result = run(CheckFights.finish(kill, EndReason.PLAYER_DIED));

		assertTrue(result.passed());
		assertTrue(result.skipped());
	}

	@Test
	public void aShortP3IsNoEvidence()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3().ticks(10);

		assertTrue(run(CheckFights.finish(kill, EndReason.PLAYER_DIED)).skipped());
	}

	@Test
	public void namesHidesAndRoles()
	{
		assertEquals("P3AttackCount", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG), check.hides());
		assertEquals(Set.of(Role.YAMA_STANDARD_ATTACK, Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED), check.reportRoles());
		assertTrue(check.applies(ContractRules.of(Contract.SHARD_ACQUISITION, Rules.DEFAULT)));
	}
}
