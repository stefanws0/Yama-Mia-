package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import com.yamareviewer.testing.TestIds;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PrayerReviewProjectionTest
{
	private final PrayerReviewProjection projection = new PrayerReviewProjection();

	private PrayerReview review(KillLog log, ProjectionContext context)
	{
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		return projection.project(log, context).value();
	}

	private static List<PrayerOutcome> outcomes(PrayerReview review)
	{
		return review.getResults().stream().map(AttackResult::getOutcome).collect(Collectors.toList());
	}

	private static List<Integer> switches(PrayerReview review)
	{
		return review.getResults().stream().map(AttackResult::getSwitchTicks).collect(Collectors.toList());
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.PRAYER_REVIEW, projection.key());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED), projection.requiredRoles());
	}

	@Test
	public void blockedAttacksAndTheSwitchTimingBetweenThem()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 2).ticks(5);
		kill.prayers(ProtectionPrayer.MISSILES).yamaCasts(Style.RANGED).ticks(2).impactOn(Actor.SELF, Style.RANGED).hitsplatOn(Actor.SELF, 0).ticks(5);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(54));

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.BLOCKED), outcomes(review));
		assertEquals(Arrays.asList(null, 7), switches(review));
		assertEquals(List.of(40, 47), review.getResults().stream().map(AttackResult::getCheckTick).collect(Collectors.toList()));
		assertEquals(Optional.of(1.0), review.accuracy());
		assertEquals(PrayerReviewProjection.LOST_ALTERNATION_LABEL, review.getLostAlternationLabel());
		assertTrue(review.getNotes().isEmpty());
	}

	@Test
	public void everyMistakeKind()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(4);
		kill.prayers(ProtectionPrayer.MISSILES).ticks(3);
		kill.prayers().yamaCasts(Style.RANGED).ticks(7);
		kill.yamaCasts(Style.MAGIC).ticks(1);
		kill.prayers(ProtectionPrayer.MAGIC).ticks(6);
		kill.yamaCasts(Style.RANGED).ticks(1);
		kill.prayers().ticks(6);
		kill.yamaCasts(Style.MAGIC).ticks(1);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(69));

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.TOO_EARLY, PrayerOutcome.LATE, PrayerOutcome.LOST_ALTERNATION, PrayerOutcome.NO_PRAYER),
			outcomes(review));
		assertEquals(Arrays.asList(null, 4, 8, null, null), switches(review));
		assertEquals(5, review.scored());
		assertEquals(4, review.mistakes());
	}

	@Test
	public void prayersDisabledByAWaveIsNotAMistake()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(4);
		kill.prayers().prayersDisabledMessage().ticks(3);
		kill.yamaCasts(Style.RANGED).ticks(7);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(54));

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.PRAYER_DISABLED), outcomes(review));
		assertEquals(1, review.scored());
		assertEquals(0, review.mistakes());
		assertEquals(Optional.of(1.0), review.accuracy());
	}

	@Test
	public void partnerAttacksAreNotScoredButSetTheSwitchOrigin()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.PARTNER, Style.MAGIC).hitsplatOn(Actor.PARTNER, 3).ticks(3);
		kill.prayers(ProtectionPrayer.MISSILES).ticks(2);
		kill.yamaCasts(Style.RANGED).ticks(2).impactOn(Actor.SELF, Style.RANGED).hitsplatOn(Actor.SELF, 0).ticks(5);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.duo(54));

		assertEquals(List.of(PrayerOutcome.BLOCKED), outcomes(review));
		assertEquals(List.of(5), switches(review));
		assertEquals(47, review.getResults().get(0).getAttack().getCastTick());
	}

	@Test
	public void attacksWithUnknownStyleAreNotScored()
	{
		KillLog log = Fights.throughToP3().yamaAnimates(TestIds.id(Role.YAMA_STANDARD_ATTACK)).ticks(2).hitsplatOn(Actor.SELF, 3).ticks(5)
			.end(EndReason.YAMA_DIED);

		PrayerReview review = review(log, TestContext.withPhases(47));

		assertTrue(review.getResults().isEmpty());
		assertEquals(1, review.getUnscored());
		assertEquals(Optional.empty(), review.accuracy());
	}

	@Test
	public void hitsplatCheckUsesTheLandingTickAndFallsBackToTheCastTick()
	{
		KillLogBuilder kill = Fights.throughToP3();
		kill.yamaCasts(Style.MAGIC).ticks(2);
		kill.prayers(ProtectionPrayer.MAGIC).impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 1).ticks(1);
		kill.prayers().ticks(4);
		kill.prayers(ProtectionPrayer.MISSILES).yamaCasts(Style.RANGED).ticks(7);
		KillLog log = kill.end(EndReason.YAMA_DIED);
		Rules hitsplat = Rules.DEFAULT.toBuilder().prayerCheck(PrayerCheck.HITSPLAT).build();

		assertEquals(List.of(PrayerOutcome.BLOCKED, PrayerOutcome.BLOCKED), outcomes(review(log, TestContext.withPhases(hitsplat, 54))));
		assertEquals(List.of(42, 47), review(log, TestContext.withPhases(hitsplat, 54)).getResults().stream()
			.map(AttackResult::getCheckTick).collect(Collectors.toList()));
		assertEquals(List.of(PrayerOutcome.NO_PRAYER, PrayerOutcome.BLOCKED), outcomes(review(log, TestContext.withPhases(54))));
	}

	@Test
	public void checkTickPastTheEndOfTheLogScoresNoPrayer()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).end(EndReason.PLAYER_DIED);
		Rules offset = Rules.DEFAULT.toBuilder().prayerCheckOffset(2).build();

		PrayerReview review = review(log, TestContext.withPhases(offset, 40));

		assertEquals(List.of(PrayerOutcome.NO_PRAYER), outcomes(review));
		assertEquals(42, review.getResults().get(0).getCheckTick());
	}

	@Test
	public void contractLabelsAndNotes()
	{
		KillLog log = Fights.throughToP3().prayers(ProtectionPrayer.MELEE).yamaCasts(Style.MAGIC).ticks(7).end(EndReason.YAMA_DIED);

		PrayerReview shard = review(log, TestContext.under(Contract.SHARD_ACQUISITION, 47));
		PrayerReview divine = review(log, TestContext.under(Contract.DIVINE_SEVERANCE, 47));

		assertEquals(PrayerReviewProjection.WRONG_PRAYER_LABEL, shard.getLostAlternationLabel());
		assertEquals("wrong prayer", shard.label(PrayerOutcome.LOST_ALTERNATION));
		assertTrue(shard.getNotes().get(0).contains("random"));
		assertEquals(PrayerReviewProjection.LOST_ALTERNATION_LABEL, divine.getLostAlternationLabel());
		assertTrue(divine.getNotes().get(0).contains("switch off 2 ticks after activation"));
	}

	@Test
	public void hiddenUpstreamSectionsHideTheReviewWithTheSameReason()
	{
		KillLog log = Fights.throughToP3().yamaCasts(Style.MAGIC).ticks(7).end(EndReason.YAMA_DIED);
		ProjectionContext context = TestContext.withPhases(47);
		context.put(Sections.ATTACKS, Section.<AttackTimeline>hidden(HiddenReason.IDS_NOT_CAPTURED));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, context).hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), projection.project(log, TestContext.empty()).hiddenReason());
	}
}
