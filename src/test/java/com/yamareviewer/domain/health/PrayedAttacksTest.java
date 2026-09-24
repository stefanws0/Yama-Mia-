package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PrayedAttacksTest
{
	private static List<Attack> attacks(KillLog log, Rules rules)
	{
		return CheckFights.context(log, rules).value(Sections.ATTACKS).orElseThrow().getAttacks();
	}

	@Test
	public void readsSelfPrayersAtTheCastTickAndLeavesOtherTargetsOut()
	{
		KillLogBuilder kill = CheckFights.duoUntilP3();
		CheckFights.attack(kill, Style.MAGIC, Actor.SELF, 2, ProtectionPrayer.MAGIC);
		CheckFights.attack(kill, Style.RANGED, Actor.SELF, 9, ProtectionPrayer.MELEE);
		CheckFights.attack(kill, Style.MAGIC, Actor.PARTNER, 9);
		KillLog log = CheckFights.finish(kill, EndReason.YAMA_DIED);

		List<PrayedAttack> prayed = PrayedAttacks.onSelf(attacks(log, Rules.DEFAULT), log, Rules.DEFAULT, Phase.P3);

		assertEquals(2, prayed.size());
		assertEquals(CheckFights.P3_START, prayed.get(0).getCheckTick());
		assertTrue(prayed.get(0).prayedCorrectly());
		assertTrue(prayed.get(0).prayedAgainstMagicOrRanged());
		assertEquals(2, prayed.get(0).damage());
		assertEquals(ProtectionPrayer.MISSILES, prayed.get(1).correctPrayer());
		assertFalse(prayed.get(1).prayedCorrectly());
		assertFalse(prayed.get(1).prayedAgainstMagicOrRanged());
		assertEquals(9, prayed.get(1).damage());
	}

	@Test
	public void hitsplatModeUsesTheLandingTickPlusTheOffset()
	{
		Rules rules = Rules.DEFAULT.toBuilder().prayerCheck(PrayerCheck.HITSPLAT).prayerCheckOffset(1).build();
		KillLog log = CheckFights.finish(CheckFights.blockedAttacks(CheckFights.soloUntilP3(), 2), EndReason.YAMA_DIED);

		List<PrayedAttack> prayed = PrayedAttacks.onSelf(attacks(log, rules), log, rules, null);

		assertEquals(2, prayed.size());
		assertEquals(CheckFights.P3_START + 3, prayed.get(0).getCheckTick());
	}
}
