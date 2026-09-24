package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.Fights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class OpenerProjectionTest
{
	private final OpenerProjection projection = new OpenerProjection();

	private static ProjectionContext upstream(KillLog log, ProjectionContext context)
	{
		context.put(Sections.GLYPHS, new GlyphsProjection().project(log, context));
		context.put(Sections.ATTACKS, new AttacksProjection().project(log, context));
		context.put(Sections.PRAYER_REVIEW, new PrayerReviewProjection().project(log, context));
		return context;
	}

	private static KillLog fireMajorityThenMagicOpener()
	{
		return Fights.throughToP3(3, 1).prayers(ProtectionPrayer.MAGIC).yamaCasts(Style.MAGIC).ticks(2)
			.impactOn(Actor.SELF, Style.MAGIC).hitsplatOn(Actor.SELF, 1).ticks(5).end(EndReason.YAMA_DIED);
	}

	@Test
	public void keyAndRoles()
	{
		assertEquals(Sections.OPENER, projection.key());
		assertEquals(Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED), projection.requiredRoles());
	}

	@Test
	public void fireMajorityExpectsAMagicOpenerAndYamaDelivered()
	{
		KillLog log = fireMajorityThenMagicOpener();

		Opener opener = projection.project(log, upstream(log, TestContext.withPhases(47))).value();

		assertEquals(new GlyphCount(3, 1), opener.getGlyphs());
		assertEquals(Style.MAGIC, opener.getExpectedStyle());
		assertEquals(40, opener.getFirstAttack().getCastTick());
		assertEquals(Boolean.TRUE, opener.getAsExpected());
		assertEquals(PrayerOutcome.BLOCKED, opener.getFirstOwnResult().getOutcome());
	}

	@Test
	public void shadowMajorityWithAMagicOpenerOnThePartnerIsUnexpectedAndYourFirstAttackIsTheSecondOne()
	{
		KillLogBuilder kill = Fights.throughToP3(1, 2);
		kill.yamaCasts(Style.MAGIC).ticks(2).impactOn(Actor.PARTNER, Style.MAGIC).hitsplatOn(Actor.PARTNER, 4).ticks(5);
		kill.prayers().yamaCasts(Style.RANGED).ticks(2).impactOn(Actor.SELF, Style.RANGED).hitsplatOn(Actor.SELF, 9).ticks(5);
		KillLog log = kill.end(EndReason.YAMA_DIED);

		Opener opener = projection.project(log, upstream(log, TestContext.duo(54))).value();

		assertEquals(Style.RANGED, opener.getExpectedStyle());
		assertEquals(Actor.PARTNER, opener.getFirstAttack().getTarget());
		assertEquals(Boolean.FALSE, opener.getAsExpected());
		assertEquals(47, opener.getFirstOwnResult().getAttack().getCastTick());
		assertEquals(PrayerOutcome.NO_PRAYER, opener.getFirstOwnResult().getOutcome());
	}

	@Test
	public void aGlyphTieHasNoExpectation()
	{
		KillLog log = Fights.throughToP3(2, 2).yamaCasts(Style.MAGIC).ticks(7).end(EndReason.YAMA_DIED);

		Opener opener = projection.project(log, upstream(log, TestContext.withPhases(47))).value();

		assertNull(opener.getExpectedStyle());
		assertNull(opener.getAsExpected());
		assertEquals(40, opener.getFirstAttack().getCastTick());
	}

	@Test
	public void noP3AttackLeavesTheAttackFieldsNull()
	{
		KillLog log = Fights.throughToP3(3, 0).ticks(3).end(EndReason.LEFT);

		Opener opener = projection.project(log, upstream(log, TestContext.withPhases(43))).value();

		assertEquals(Style.MAGIC, opener.getExpectedStyle());
		assertNull(opener.getFirstAttack());
		assertNull(opener.getAsExpected());
		assertNull(opener.getFirstOwnResult());
	}

	@Test
	public void hiddenUnderShardAcquisitionBecauseStylesAreRandom()
	{
		KillLog log = fireMajorityThenMagicOpener();

		assertEquals(Optional.of(HiddenReason.CONTRACT),
			projection.project(log, upstream(log, TestContext.under(Contract.SHARD_ACQUISITION, 47))).hiddenReason());
	}

	@Test
	public void hiddenGlyphsHideTheOpenerButAHiddenPrayerReviewOnlyDropsYourFirstResult()
	{
		KillLog log = fireMajorityThenMagicOpener();
		ProjectionContext noGlyphs = upstream(log, TestContext.withPhases(47));
		noGlyphs.put(Sections.GLYPHS, Section.<GlyphCount>hidden(HiddenReason.IDS_NOT_CAPTURED));
		ProjectionContext noReview = upstream(log, TestContext.withPhases(47));
		noReview.put(Sections.PRAYER_REVIEW, Section.<PrayerReview>hidden(HiddenReason.ERROR));

		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), projection.project(log, noGlyphs).hiddenReason());
		Opener opener = projection.project(log, noReview).value();
		assertEquals(Boolean.TRUE, opener.getAsExpected());
		assertNull(opener.getFirstOwnResult());
	}
}
