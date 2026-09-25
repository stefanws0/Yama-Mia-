package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CrashesProjectionTest
{
	private final CrashesProjection projection = new CrashesProjection();

	private CrashSummary crashes(KillLog log, ProjectionContext context)
	{
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		return projection.project(log, context).value();
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.CRASHES, projection.key());
		assertEquals(Set.of(Role.CRASH_FIREBALL), projection.requiredRoles());
	}

	@Test
	public void aLineIsDodgedWhenNothingHitsWithinTheWindow()
	{
		KillLog log = Fights.throughToP3().ticks(3).crashLine(3200, 3205).ticks(2).hitsplatOn(Actor.SELF, 9).ticks(3).end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.withPhases(48));

		assertEquals(List.of(new CrashLine(43, Phase.P3, Actor.SELF, 1, 1, new Position(3200, 3205, 0), false, 0)), summary.getLines());
		assertEquals(1, summary.dodged(Actor.SELF));
	}

	@Test
	public void aLineIsHitByTheImpactGraphicOrByANonStandardHitsplat()
	{
		KillLogBuilder kill = Fights.throughToP3().ticks(3);
		kill.crashLine(3200, 3205).ticks(1).crashImpactOn(Actor.SELF).hitsplatOn(Actor.SELF, 12).ticks(3);
		kill.crashLine(3200, 3206).ticks(1).hitsplatOn(Actor.SELF, 10).ticks(3);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.withPhases(51));

		assertEquals(List.of(true, true), summary.getLines().stream().map(CrashLine::isHit).collect(Collectors.toList()));
		assertEquals(List.of(12, 10), summary.getLines().stream().map(CrashLine::getDamage).collect(Collectors.toList()));
		assertEquals(0, summary.dodged(Actor.SELF));
		assertEquals(22, summary.damage(Actor.SELF));
	}

	@Test
	public void aStandardAttacksLandingHitsplatIsNotACrashHit()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 2)
			.crashLine(3200, 3203).ticks(5).end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.withPhases(47));

		assertFalse(summary.getLines().get(0).isHit());
	}

	@Test
	public void linesWithinTheSetGapFormASetAndALaterLineStartsTheNext()
	{
		KillLogBuilder kill = Fights.throughToP3().ticks(3);
		kill.crashLine(3200, 3205).ticks(4).crashLine(3203, 3205).ticks(4).crashLine(3200, 3208).ticks(29);
		kill.crashLine(3200, 3205).ticks(2);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.withPhases(82));

		assertEquals(List.of(1, 1, 1, 2), summary.getLines().stream().map(CrashLine::getSet).collect(Collectors.toList()));
		assertEquals(List.of(1, 2, 3, 1), summary.getLines().stream().map(CrashLine::getIndexInSet).collect(Collectors.toList()));
		assertEquals(2, summary.sets());
		assertEquals(4, summary.total(Actor.SELF));
	}

	@Test
	public void twoLinesOnOneTickBelongToDifferentPlayers()
	{
		KillLog log = Fights.throughToP3().partnerAt(3230, 3200).ticks(3).crashLine(3200, 3203).crashLine(3230, 3203).ticks(3).end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.duo(46));

		assertEquals(2, summary.getLines().size());
		assertEquals(1, summary.total(Actor.SELF));
		assertEquals(1, summary.total(Actor.PARTNER));
		assertEquals(List.of(1, 1), summary.getLines().stream().map(CrashLine::getIndexInSet).collect(Collectors.toList()));
		assertEquals(new Position(3230, 3203, 0), summary.forPlayer(Actor.PARTNER).get(0).getCentre());
	}

	@Test
	public void soloLinesAlwaysBelongToYou()
	{
		KillLog log = Fights.throughToP3().ticks(3).crashLine(3260, 3260).ticks(3).end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.withPhases(46));

		assertEquals(Actor.SELF, summary.getLines().get(0).getPlayer());
		assertTrue(summary.forPlayer(Actor.PARTNER).isEmpty());
	}

	@Test
	public void hiddenAttacksHideTheCrashesWithTheSameReason()
	{
		KillLog log = Fights.throughToP3().ticks(3).crashLine(3200, 3205).ticks(3).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.withPhases(46);
		context.put(Sections.ATTACKS, Section.<AttackTimeline>hidden(HiddenReason.IDS_NOT_CAPTURED));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, context).hiddenReason());
	}

	@Test
	public void aCrashHitOnYouCountsAlthoughItIsFlaggedMine()
	{
		KillLog log = Fights.throughToP3().ticks(3).crashLine(3200, 3205).ticks(1).myHitOn(Actor.SELF, 11).ticks(3).end(EndReason.YAMA_DIED);

		CrashSummary summary = crashes(log, TestContext.withPhases(47));

		assertTrue(log.eventsOf(HitsplatObserved.class).stream().filter(hit -> Actor.SELF.equals(hit.getTarget())).allMatch(HitsplatObserved::isMine));
		assertTrue(summary.getLines().get(0).isHit());
		assertEquals(11, summary.damage(Actor.SELF));
		assertEquals(0, summary.dodged(Actor.SELF));
	}
}
