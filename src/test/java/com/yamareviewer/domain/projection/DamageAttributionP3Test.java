package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Runs the whole standard projection list, so Part 2's rules 2 and 5-10 and this part's rules 1, 3 and 4 meet. */
public class DamageAttributionP3Test
{
	private static DamageSummary damage(KillLog log)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), TestIds.registry(), Rules.DEFAULT);
		return builder.run(log, ReviewSettings.DEFAULT).value(Sections.DAMAGE).orElseThrow(() -> new AssertionError("damage hidden"));
	}

	@Test
	public void standardCrashWaveAndMeleeAreToldApartInOrder()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 2).ticks(3);
		kill.crashLine(3200, 3203).ticks(1).hitsplatOn(Actor.SELF, 12).ticks(4);
		kill.waveOn(Actor.SELF).ticks(1).hitsplatOn(Actor.SELF, 8).ticks(4);
		kill.yamaAnimates(TestIds.id(Role.YAMA_MELEE)).ticks(1).crashLine(3200, 3203).hitsplatOn(Actor.SELF, 20).ticks(4);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		Map<DamageSource, Integer> bySource = damage(log).bySource(Actor.SELF);

		assertEquals(Integer.valueOf(2), bySource.get(DamageSource.STANDARD));
		assertEquals(Integer.valueOf(12), bySource.get(DamageSource.SHADOW_CRASH));
		assertEquals(Integer.valueOf(8), bySource.get(DamageSource.SHADOW_WAVE));
		assertEquals(Integer.valueOf(20), bySource.get(DamageSource.MELEE));
		assertEquals(42, damage(log).total(Actor.SELF));
	}

	@Test
	public void withoutCrashOrWaveSectionsTheOldRulesStillApply()
	{
		KillLog log = Fights.throughToP3().ticks(3).hitsplatOn(Actor.SELF, 7).ticks(3).end(EndReason.YAMA_DIED);

		assertEquals(Integer.valueOf(7), damage(log).bySource(Actor.SELF).get(DamageSource.OTHER));
	}

	@Test
	public void hitsOnYouFlaggedMineAreAttributedToTheirSources()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.SELF, Style.MAGIC).myHitOn(Actor.SELF, 3).ticks(3);
		kill.crashLine(3200, 3203).ticks(1).myHitOn(Actor.SELF, 12).ticks(4);
		kill.waveOn(Actor.SELF).ticks(1).myHitOn(Actor.SELF, 8).ticks(4);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		Map<DamageSource, Integer> bySource = damage(log).bySource(Actor.SELF);

		assertTrue(log.eventsOf(HitsplatObserved.class).stream().filter(hit -> Actor.SELF.equals(hit.getTarget())).allMatch(HitsplatObserved::isMine));
		assertEquals(Integer.valueOf(3), bySource.get(DamageSource.STANDARD));
		assertEquals(Integer.valueOf(12), bySource.get(DamageSource.SHADOW_CRASH));
		assertEquals(Integer.valueOf(8), bySource.get(DamageSource.SHADOW_WAVE));
	}
}
