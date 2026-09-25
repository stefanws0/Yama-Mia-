package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.ids.PrayerCheck;
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

public class BlockedDamageCheckTest
{
	private final BlockedDamageCheck check = new BlockedDamageCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	@Test
	public void blockedHitsWithinTheMaxPass()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void twoHardBlockedHitsFail()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 4);
		CheckFights.attack(kill, Style.MAGIC, Actor.SELF, 12, CheckFights.correct(Style.MAGIC));
		CheckFights.blockedAttacks(kill, 3);
		CheckFights.attack(kill, Style.RANGED, Actor.SELF, 15, CheckFights.correct(Style.RANGED));
		CheckFights.blockedAttacks(kill, 2);

		CheckResult result = run(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("2 of 11"));
		assertEquals(OptionalInt.of(CheckFights.P3_START + 4 * CheckFights.CYCLE), result.tick());
	}

	@Test
	public void oneHardBlockedHitIsTolerated()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 4);
		CheckFights.attack(kill, Style.MAGIC, Actor.SELF, 12, CheckFights.correct(Style.MAGIC));
		CheckFights.blockedAttacks(kill, 5);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void wrongPrayerHitsAreNotBlockedAttacks()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		for (int i = 0; i < 6; i++)
		{
			Style style = CheckFights.alternating(i);
			CheckFights.attack(kill, style, Actor.SELF, 20, CheckFights.wrong(style));
		}

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.YAMA_DIED)));
	}

	@Test
	public void hitsplatModeChecksTheLandingTick()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		for (int i = 0; i < 3; i++)
		{
			Style style = CheckFights.alternating(i);
			kill.prayers().yamaTarget(Actor.SELF)
				.yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).graphicOn(Actor.YAMA, CheckFights.castGraphic(style))
				.ticks(2)
				.prayers(CheckFights.correct(style)).graphicOn(Actor.SELF, CheckFights.impactGraphic(style)).hitsplatOn(Actor.SELF, 12)
				.ticks(CheckFights.CYCLE - 2);
		}
		KillLog log = CheckFights.finish(kill, EndReason.YAMA_DIED);
		Rules hitsplat = Rules.DEFAULT.toBuilder().prayerCheck(PrayerCheck.HITSPLAT).build();

		assertEquals(CheckResult.pass(), check.check(log, CheckFights.context(log)));
		assertFalse(check.check(log, CheckFights.context(log, hitsplat)).passed());
	}

	@Test
	public void skippedWhenBlockedMaxHitIsNoneAndHidesThePrayerReview()
	{
		assertFalse(check.applies(ContractRules.of(Contract.SENSORY_CLOUDING, Rules.DEFAULT)));
		assertFalse(check.applies(ContractRules.of(Contract.BLOODIED_BLOWS, Rules.DEFAULT)));
		assertFalse(check.applies(ContractRules.of(Contract.FAMILIAR_ACQUISITION, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.NONE, Rules.DEFAULT)));
		assertTrue(check.applies(ContractRules.of(Contract.DIVINE_SEVERANCE, Rules.DEFAULT)));
		assertEquals("BlockedDamage", check.name());
		assertEquals(List.of(Sections.PRAYER_REVIEW), check.hides());
		assertEquals(Set.of(Role.IMPACT_MAGIC, Role.IMPACT_RANGED), check.reportRoles());
	}

	@Test
	public void hitsOnYouFlaggedMineAreEvidence()
	{
		KillLogBuilder kill = CheckFights.soloUntilP3();
		CheckFights.blockedAttacks(kill, 4);
		CheckFights.attack(kill, Style.MAGIC, Actor.SELF, 12, CheckFights.correct(Style.MAGIC));
		CheckFights.blockedAttacks(kill, 3);
		CheckFights.attack(kill, Style.RANGED, Actor.SELF, 15, CheckFights.correct(Style.RANGED));
		CheckFights.blockedAttacks(kill, 2);
		KillLog log = CheckFights.finish(kill, EndReason.YAMA_DIED);

		assertTrue(log.eventsOf(HitsplatObserved.class).stream().anyMatch(hit -> Actor.SELF.equals(hit.getTarget()) && hit.getAmount() > 0));
		assertTrue(log.eventsOf(HitsplatObserved.class).stream().filter(hit -> Actor.SELF.equals(hit.getTarget())).allMatch(HitsplatObserved::isMine));
		assertFalse(run(log).passed());
	}
}
