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

public class GraphicVsBehaviourCheckTest
{
	private final GraphicVsBehaviourCheck check = new GraphicVsBehaviourCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	/** {@code count} alternating attacks on SELF hitting for {@code damage}, praying the graphic's style or the other one. */
	private static KillLogBuilder attacks(KillLogBuilder kill, int count, int damage, boolean prayGraphicStyle)
	{
		for (int i = 0; i < count; i++)
		{
			Style style = CheckFights.alternating(i);
			CheckFights.attack(kill, style, Actor.SELF, damage, prayGraphicStyle ? CheckFights.correct(style) : CheckFights.wrong(style));
		}
		return kill;
	}

	@Test
	public void aHealthyKillPasses()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void realPrayerMistakesAreNotContradictions()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 7);
		attacks(kill, 5, 20, false);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void swappedGraphicsFail()
	{
		KillLogBuilder kill = attacks(CheckFights.soloUntilP3(), 12, 20, true);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("12 of 12"));
		assertEquals(OptionalInt.of(CheckFights.P3_START), result.tick());
	}

	@Test
	public void fewerThanThreeContradictionsPass()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		attacks(kill, 2, 20, true);
		attacks(kill, 4, 20, false);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void threeContradictionsAmongManyPass()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		attacks(kill, 3, 20, true);
		attacks(kill, 17, 20, false);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void threeOfEightFail()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		attacks(kill, 3, 20, true);
		attacks(kill, 5, 20, false);
		CheckFights.blockedAttacks(kill, 4);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("3 of 8"));
	}

	@Test
	public void evidenceFromEveryPhaseCounts()
	{
		KillLogBuilder kill = CheckFights.solo();
		for (int i = 0; i < 3; i++)
		{
			Style style = CheckFights.alternating(i);
			CheckFights.attack(kill, style, Actor.SELF, 20, CheckFights.correct(style)).ticks(1);
		}
		CheckFights.untilP3(kill);
		attacks(kill, 5, 20, false);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("3 of 8"));
		assertEquals(OptionalInt.of(0), result.tick());
	}

	@Test
	public void skippedWhenBlockedMaxHitIsNoneAndHidesPrayerReviewAndOpener()
	{
		assertFalse(check.applies(ContractRules.of(Contract.SENSORY_CLOUDING, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.NONE, Rules.DEFAULT)));
		assertEquals("GraphicVsBehaviour", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW, Sections.OPENER), check.hides());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED), check.reportRoles());
	}
}
