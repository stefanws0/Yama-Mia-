package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import java.util.List;
import java.util.Optional;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Pins the assumptions the health-check tests make about how Parts 2 and 3 read a synthetic fight. */
public class CheckFightsTest
{
	@Test
	public void aHealthySoloKillProjectsAllPhasesAttacksCrashesAndSpecs()
	{
		ProjectionContext context = CheckFights.context(CheckFights.healthySolo());

		PhaseTimes phases = context.value(Sections.PHASES).orElseThrow();
		assertEquals(List.of(Phase.P1, Phase.JUDGE_1, Phase.P2, Phase.JUDGE_2, Phase.P3),
			phases.getSpans().stream().map(PhaseSpan::getPhase).collect(toList()));
		assertEquals(CheckFights.P3_START, phases.span(Phase.P3).orElseThrow().getStartTick());

		List<Attack> attacks = context.value(Sections.ATTACKS).orElseThrow().getAttacks();
		assertEquals(20, attacks.stream().filter(attack -> attack.getPhase() == Phase.P3).count());
		Attack first = attacks.get(0);
		assertEquals(CheckFights.P3_START, first.getCastTick());
		assertEquals(Style.MAGIC, first.getStyle());
		assertEquals(Actor.SELF, first.getTarget());
		assertEquals(Integer.valueOf(CheckFights.P3_START + 2), first.getLandingTick());
		assertEquals(Integer.valueOf(2), first.getDamage());
		assertEquals(Style.RANGED, attacks.get(1).getStyle());

		assertEquals(3, context.value(Sections.CRASHES).orElseThrow().getLines().size());
		assertTrue(context.section(Sections.SPECS).isOk());
		assertTrue(context.section(Sections.PRAYER_REVIEW).isOk());
		assertTrue(context.section(Sections.SUPPLIES).isOk());
		assertEquals(Optional.of(Mode.SOLO), context.value(Sections.MODE));
	}

	@Test
	public void aDuoKillHasAPartnerAndAttacksOnBoth()
	{
		var kill = CheckFights.duoUntilP3();
		CheckFights.attack(kill, Style.MAGIC, Actor.SELF, 2, CheckFights.correct(Style.MAGIC));
		CheckFights.attack(kill, Style.RANGED, Actor.PARTNER, 2);
		ProjectionContext context = CheckFights.context(CheckFights.finish(kill, EndReason.YAMA_DIED));

		assertEquals(Optional.of(Mode.DUO_HOST), context.value(Sections.MODE));
		List<Attack> attacks = context.value(Sections.ATTACKS).orElseThrow().getAttacks();
		assertEquals(Actor.SELF, attacks.get(0).getTarget());
		assertEquals(Actor.PARTNER, attacks.get(1).getTarget());
	}
}
