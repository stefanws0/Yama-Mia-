package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DamageSummaryTest
{
	private final DamageSummary summary = new DamageSummary(List.of(
		new DamageHit(10, Phase.P1, Actor.SELF, DamageSource.MELEE, 20),
		new DamageHit(12, Phase.P1, Actor.PARTNER, DamageSource.MELEE_SPLASH, 15),
		new DamageHit(300, Phase.P3, Actor.SELF, DamageSource.FLARE, 40),
		new DamageHit(301, Phase.P3, Actor.SELF, DamageSource.MELEE, 5),
		new DamageHit(305, null, Actor.SELF, DamageSource.OTHER, 1)));

	@Test
	public void totalsPerPlayerAndPhase()
	{
		assertEquals(66, summary.total(Actor.SELF));
		assertEquals(15, summary.total(Actor.PARTNER));
		assertEquals(20, summary.total(Actor.SELF, Phase.P1));
		assertEquals(45, summary.total(Actor.SELF, Phase.P3));
		assertEquals(0, summary.total(Actor.PARTNER, Phase.P3));
	}

	@Test
	public void bySourceOmitsSourcesWithoutDamage()
	{
		Map<DamageSource, Integer> self = summary.bySource(Actor.SELF);

		assertEquals(Integer.valueOf(25), self.get(DamageSource.MELEE));
		assertEquals(Integer.valueOf(40), self.get(DamageSource.FLARE));
		assertEquals(Integer.valueOf(1), self.get(DamageSource.OTHER));
		assertFalse(self.containsKey(DamageSource.SHADOW_CRASH));
		assertEquals(List.of(DamageSource.MELEE, DamageSource.FLARE, DamageSource.OTHER), List.copyOf(self.keySet()));
	}

	@Test
	public void sharedMechanicsAreMeleeSplashSpecialsAndFlares()
	{
		assertTrue(DamageSource.MELEE_SPLASH.shared());
		assertTrue(DamageSource.SPECIAL.shared());
		assertTrue(DamageSource.FLARE.shared());
		assertFalse(DamageSource.MELEE.shared());
		assertFalse(DamageSource.STANDARD.shared());
		assertEquals(55, summary.shared());
	}
}
