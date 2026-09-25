package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AttacksProjectionTest
{
	private final AttacksProjection projection = new AttacksProjection();

	private List<Attack> attacksOf(KillLog log, ProjectionContext context)
	{
		return projection.project(log, context).value().getAttacks();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.ATTACKS, projection.key());
		assertEquals(Set.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK), projection.requiredRoles());
	}

	@Test
	public void styleFromTheCastGraphicTargetFromTheTickStateAndTheLandingHitsplat()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(2).hitsplatOn(Actor.SELF, 2).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new Attack(40, Phase.P3, Style.MAGIC, Actor.SELF, 42, 2)), attacksOf(log, TestContext.withPhases(47)));
	}

	@Test
	public void castGraphicOnTheNextTickStillCounts()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(1)
			.graphicOn(Actor.YAMA, TestIds.id(Role.YAMA_CAST_RANGED)).ticks(1).hitsplatOn(Actor.SELF, 0).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new Attack(40, Phase.P3, Style.RANGED, Actor.SELF, 42, 0)), attacksOf(log, TestContext.withPhases(47)));
	}

	@Test
	public void styleAndTargetFromTheImpactWhenThereIsNoCastGraphic()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(3)
			.impactOn(Actor.PARTNER, Style.RANGED).hitsplatOn(Actor.PARTNER, 5).ticks(4).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new Attack(40, Phase.P3, Style.RANGED, Actor.PARTNER, 43, 5)), attacksOf(log, TestContext.duo(47)));
	}

	@Test
	public void theImpactWinsOverTheTickStateForTheTarget()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).yamaTarget(Actor.PARTNER).ticks(2)
			.impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 1).ticks(5).end(EndReason.YAMA_DIED);

		assertEquals(Actor.SELF, attacksOf(log, TestContext.duo(47)).get(0).getTarget());
	}

	@Test
	public void styleStaysNullWithoutCastOrImpact()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(2)
			.hitsplatOn(Actor.SELF, 3).ticks(5).end(EndReason.YAMA_DIED);

		Attack attack = attacksOf(log, TestContext.withPhases(47)).get(0);

		assertNull(attack.getStyle());
		assertEquals(Actor.SELF, attack.getTarget());
		assertEquals(Integer.valueOf(42), attack.getLandingTick());
	}

	@Test
	public void anImpactMoreThanThreeTicksAfterTheCastIsNotThisAttacks()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(4)
			.impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 3).ticks(3).end(EndReason.YAMA_DIED);

		Attack attack = attacksOf(log, TestContext.withPhases(47)).get(0);

		assertNull(attack.getStyle());
		assertNull(attack.getLandingTick());
		assertNull(attack.getDamage());
	}

	@Test
	public void yourOwnHitsplatsAndHitsOnOtherActorsAreNotTheLanding()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(2)
			.myHitOn(Actor.YAMA, 40).hitsplatOn(Actor.PARTNER, 9).ticks(1).hitsplatOn(Actor.SELF, 2).ticks(4).end(EndReason.YAMA_DIED);

		Attack attack = attacksOf(log, TestContext.withPhases(47)).get(0);

		assertEquals(Integer.valueOf(43), attack.getLandingTick());
		assertEquals(Integer.valueOf(2), attack.getDamage());
	}

	@Test
	public void attacksInEveryPhaseAreKeptWithTheirPhase()
	{
		KillLog log = KillLogBuilder.kill().ticks(5).yamaCasts(Style.RANGED).ticks(3).end(EndReason.LEFT);

		List<Attack> attacks = attacksOf(log, TestContext.withPhases(8));

		assertEquals(1, attacks.size());
		assertEquals(Phase.P1, attacks.get(0).getPhase());
		assertNull(attacksOf(log, TestContext.empty()).get(0).getPhase());
	}

	@Test
	public void attacksComeInCastOrderAndOtherAnimationsAreIgnored()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(7).yamaAnimates(TestIds.id(Role.YAMA_MELEE)).ticks(2)
			.yamaCasts(Style.RANGED).ticks(3).end(EndReason.YAMA_DIED);

		AttackTimeline timeline = projection.project(log, TestContext.withPhases(52)).value();

		assertEquals(List.of(40, 49), List.of(timeline.getAttacks().get(0).getCastTick(), timeline.getAttacks().get(1).getCastTick()));
		assertEquals(java.util.Optional.of(Style.RANGED), timeline.expectedStyle(1));
	}

	@Test
	public void aHitOnYouIsTheLandingAlthoughItIsFlaggedMine()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.RANGED).ticks(2).myHitOn(Actor.SELF, 7).ticks(5).end(EndReason.YAMA_DIED);

		assertTrue(log.eventsOf(HitsplatObserved.class).stream().filter(hit -> Actor.SELF.equals(hit.getTarget())).allMatch(HitsplatObserved::isMine));
		assertEquals(List.of(new Attack(40, Phase.P3, Style.RANGED, Actor.SELF, 42, 7)), attacksOf(log, TestContext.withPhases(47)));
	}
}
