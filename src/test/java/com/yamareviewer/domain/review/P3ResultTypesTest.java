package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class P3ResultTypesTest
{
	private static Attack attack(int tick, Style style, Actor target)
	{
		return new Attack(tick, Phase.P3, style, target, tick + 2, 2);
	}

	@Test
	public void expectedStyleIsTheOppositeOfThePreviousAttackWhoeverItTargeted()
	{
		AttackTimeline timeline = new AttackTimeline(List.of(
			attack(40, Style.MAGIC, Actor.SELF),
			attack(47, Style.RANGED, Actor.PARTNER),
			attack(54, null, Actor.SELF),
			attack(61, Style.MAGIC, Actor.SELF)));

		assertEquals(Optional.empty(), timeline.expectedStyle(0));
		assertEquals(Optional.of(Style.RANGED), timeline.expectedStyle(1));
		assertEquals(Optional.of(Style.MAGIC), timeline.expectedStyle(2));
		assertEquals(Optional.empty(), timeline.expectedStyle(3));
		assertEquals(3, timeline.inPhase(Phase.P3).size() - 1);
	}

	@Test
	public void glyphMajorityGivesTheExpectedOpener()
	{
		assertEquals(Optional.of(Style.MAGIC), new GlyphCount(3, 1).expectedOpener());
		assertEquals(Optional.of(Style.RANGED), new GlyphCount(1, 2).expectedOpener());
		assertEquals(Optional.empty(), new GlyphCount(2, 2).expectedOpener());
	}

	@Test
	public void mistakesAreEverythingButBlockedAndDisabled()
	{
		assertFalse(PrayerOutcome.BLOCKED.isMistake());
		assertFalse(PrayerOutcome.PRAYER_DISABLED.isMistake());
		assertTrue(PrayerOutcome.TOO_EARLY.isMistake());
		assertTrue(PrayerOutcome.LATE.isMistake());
		assertTrue(PrayerOutcome.LOST_ALTERNATION.isMistake());
		assertTrue(PrayerOutcome.NO_PRAYER.isMistake());
	}

	@Test
	public void prayerReviewCountsAndAccuracy()
	{
		PrayerReview review = new PrayerReview(List.of(
			new AttackResult(attack(40, Style.MAGIC, Actor.SELF), 40, PrayerOutcome.BLOCKED, null),
			new AttackResult(attack(47, Style.RANGED, Actor.SELF), 47, PrayerOutcome.TOO_EARLY, 3),
			new AttackResult(attack(54, Style.MAGIC, Actor.SELF), 54, PrayerOutcome.PRAYER_DISABLED, null),
			new AttackResult(attack(61, Style.RANGED, Actor.SELF), 61, PrayerOutcome.BLOCKED, 5)),
			1, "wrong prayer", List.of());

		assertEquals(3, review.scored());
		assertEquals(2, review.blocked());
		assertEquals(1, review.mistakes());
		assertEquals(1, review.count(PrayerOutcome.TOO_EARLY));
		assertEquals(Optional.of(2.0 / 3.0), review.accuracy());
		assertEquals("wrong prayer", review.label(PrayerOutcome.LOST_ALTERNATION));
		assertEquals("too early", review.label(PrayerOutcome.TOO_EARLY));
		assertEquals(Optional.empty(), new PrayerReview(List.of(), 0, "lost alternation", List.of()).accuracy());
	}

	@Test
	public void tickLogShareOfGapsOnTheCycle()
	{
		TickLog log = new TickLog(List.of(
			new TickLogEntry(40, Style.MAGIC, Actor.SELF, null, List.of()),
			new TickLogEntry(47, Style.RANGED, Actor.SELF, 7, List.of()),
			new TickLogEntry(56, Style.MAGIC, Actor.SELF, 9, List.of("crash line at 50")),
			new TickLogEntry(63, Style.RANGED, Actor.SELF, 7, List.of())), 7);

		assertEquals(3, log.gaps());
		assertEquals(2, log.gapsOnCycle());
		assertEquals(Optional.of(2.0 / 3.0), log.cycleShare());
		assertEquals(Optional.empty(), new TickLog(List.of(), 7).cycleShare());
	}

	@Test
	public void crashSummaryPerPlayer()
	{
		Position centre = new Position(3200, 3200, 0);
		CrashSummary summary = new CrashSummary(List.of(
			new CrashLine(50, Phase.P3, Actor.SELF, 1, 1, centre, false, 0),
			new CrashLine(54, Phase.P3, Actor.SELF, 1, 2, centre, true, 12),
			new CrashLine(58, Phase.P3, Actor.SELF, 1, 3, centre, false, 0),
			new CrashLine(50, Phase.P3, Actor.PARTNER, 1, 1, centre, true, 8),
			new CrashLine(90, Phase.P3, Actor.SELF, 2, 1, centre, false, 0)));

		assertEquals(4, summary.total(Actor.SELF));
		assertEquals(3, summary.dodged(Actor.SELF));
		assertEquals(12, summary.damage(Actor.SELF));
		assertEquals(1, summary.total(Actor.PARTNER));
		assertEquals(0, summary.dodged(Actor.PARTNER));
		assertEquals(2, summary.sets());
	}

	@Test
	public void waveSummaryPerPlayer()
	{
		WaveSummary summary = new WaveSummary(List.of(
			new WaveHit(60, Phase.P3, Actor.SELF, true, 15, true),
			new WaveHit(64, Phase.P3, Actor.SELF, false, 0, false),
			new WaveHit(60, Phase.P3, Actor.PARTNER, false, 0, false)));

		assertEquals(2, summary.total(Actor.SELF));
		assertEquals(1, summary.dodged(Actor.SELF));
		assertEquals(15, summary.damage(Actor.SELF));
		assertEquals(1, summary.prayersDisabled(Actor.SELF));
		assertEquals(1, summary.dodged(Actor.PARTNER));
	}

	@Test
	public void specSummaryTallies()
	{
		SpecResult emberlight = new SpecResult(10, Phase.P1, Actor.SELF, SpecWeapon.EMBERLIGHT, true, Actor.YAMA, SpecOutcome.LANDED, 41, 25, null, null);
		SpecResult missed = new SpecResult(14, Phase.P1, Actor.SELF, SpecWeapon.EMBERLIGHT, true, Actor.YAMA, SpecOutcome.MISSED, 0, 25, null, null);
		SpecResult other = new SpecResult(20, Phase.P1, Actor.SELF, null, false, Actor.YAMA, SpecOutcome.LANDED, 30, 50, null, null);
		SpecResult partner = new SpecResult(30, Phase.P1, Actor.PARTNER, SpecWeapon.ELDER_MAUL, true, Actor.YAMA, SpecOutcome.UNKNOWN, null, 0, null, null);
		SpecSummary summary = new SpecSummary(List.of(emberlight, missed, other, partner), List.of(), List.of(),
			new YamaStats(225, 250, 0), new YamaStats(192, 250, 0), 191, Phase.P1);

		assertEquals(2, summary.used(SpecWeapon.EMBERLIGHT));
		assertEquals(1, summary.landed(SpecWeapon.EMBERLIGHT));
		assertEquals(List.of(SpecWeapon.EMBERLIGHT, SpecWeapon.ELDER_MAUL), summary.weaponsUsed());
		assertEquals(3, summary.ownSpecs().size());
		assertEquals(50, summary.ownEnergyUsedOnDrains());
		assertEquals(34, summary.defenceDrained());
		assertEquals(80, summary.maxDefenceDrain());
		assertEquals(Optional.of(2.0 / 3.0), summary.landedShare());
		assertEquals("Other weapon", other.weaponName());
		assertTrue(other.isOtherWeapon());
		assertTrue(new HornUse(5, Actor.SELF, null).isUnused());
	}
}
