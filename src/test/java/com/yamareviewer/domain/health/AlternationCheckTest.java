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
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AlternationCheckTest
{
	private final AlternationCheck check = new AlternationCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	private static Actor alternatingTarget(int index)
	{
		return index % 2 == 0 ? Actor.SELF : Actor.PARTNER;
	}

	@Test
	public void aHealthyKillPasses()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void globalAlternationIsEnoughInDuo()
	{
		KillLogBuilder kill = CheckFights.duoUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = CheckFights.alternating(i);
			CheckFights.attack(kill, style, alternatingTarget(i), 2, CheckFights.correct(style));
		}

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void perTargetAlternationIsEnough()
	{
		KillLogBuilder kill = CheckFights.duoUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = (i / 2) % 2 == 0 ? Style.MAGIC : Style.RANGED;
			CheckFights.attack(kill, style, alternatingTarget(i), 2, CheckFights.correct(style));
		}

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void noAlternationAnywhereFails()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		for (int i = 0; i < 12; i++)
		{
			Style style = i % 4 == 3 ? Style.RANGED : Style.MAGIC;
			CheckFights.attack(kill, style, Actor.SELF, 2, CheckFights.correct(style));
		}

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("5 of 11 pairs"));
		assertEquals(OptionalInt.of(CheckFights.P3_START + CheckFights.CYCLE), result.tick());
	}

	@Test
	public void attacksWithAnUnknownStyleAreLeftOut()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		for (int i = 0; i < 12; i++)
		{
			if (i == 2)
			{
				kill.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(CheckFights.CYCLE);
				continue;
			}
			Style style = CheckFights.alternating(i);
			CheckFights.attack(kill, style, Actor.SELF, 2, CheckFights.correct(style));
		}

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void tooFewPairsAreNoEvidence()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		for (int i = 0; i < 4; i++)
		{
			CheckFights.attack(kill, Style.MAGIC, Actor.SELF, 2, CheckFights.correct(Style.MAGIC));
		}

		assertTrue(run(CheckFights.finish(kill, EndReason.YAMA_DIED)).skipped());
	}

	@Test
	public void skippedUnderShardAcquisitionAndHidesPrayerReviewAndOpener()
	{
		assertFalse(check.applies(ContractRules.of(Contract.SHARD_ACQUISITION, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.CATALYST_ACQUISITION, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.NONE, Rules.DEFAULT)));
		assertEquals("Alternation", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW, Sections.OPENER), check.hides());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED), check.reportRoles());
	}
}
