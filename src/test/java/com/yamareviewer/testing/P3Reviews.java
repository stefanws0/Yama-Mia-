package com.yamareviewer.testing;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.DrainStep;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SpecOutcome;
import com.yamareviewer.domain.review.SpecResult;
import com.yamareviewer.domain.review.SpecSummary;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.TickLogEntry;
import com.yamareviewer.domain.review.WaveHit;
import com.yamareviewer.domain.review.WaveSummary;
import java.util.List;

/** KillReviews with the Part 3 sections filled by hand; the Part 2 sections are hidden. */
public final class P3Reviews
{
	private P3Reviews()
	{
	}

	public static KillReview.KillReviewBuilder base()
	{
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId("kill-1")
			.startEpochMs(1_000L)
			.endEpochMs(2_000L)
			.endReason(EndReason.YAMA_DIED)
			.mode(Mode.SOLO)
			.contract(Contract.NONE)
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(0)
			.phases(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.damage(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.flares(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.supplies(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.deathRecap(Section.hidden(HiddenReason.NOT_APPLICABLE));
	}

	public static KillReview allHidden()
	{
		return base()
			.prayerReview(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.opener(Section.hidden(HiddenReason.CONTRACT))
			.tickLog(Section.hidden(HiddenReason.HEALTH_CHECK_FAILED))
			.crashes(Section.hidden(HiddenReason.IDS_NOT_CAPTURED))
			.waves(Section.hidden(HiddenReason.ERROR))
			.specs(Section.hidden(HiddenReason.NOT_APPLICABLE))
			.build();
	}

	public static KillReview sample()
	{
		Attack first = new Attack(40, Phase.P3, Style.MAGIC, Actor.SELF, 42, 2);
		AttackResult firstResult = new AttackResult(first, 40, PrayerOutcome.BLOCKED, null);
		PrayerReview prayers = new PrayerReview(List.of(
			firstResult,
			new AttackResult(new Attack(47, Phase.P3, Style.RANGED, Actor.SELF, 49, 0), 47, PrayerOutcome.TOO_EARLY, 4),
			new AttackResult(new Attack(54, Phase.P3, Style.MAGIC, Actor.SELF, 56, 1), 54, PrayerOutcome.BLOCKED, 7)),
			0, "lost alternation", List.of());
		Position centre = new Position(3200, 3203, 0);
		SpecResult maul = new SpecResult(2, Phase.P1, Actor.SELF, SpecWeapon.ELDER_MAUL, true, Actor.YAMA, SpecOutcome.LANDED, 60, 50, null, null);
		return base()
			.prayerReview(Section.ok(prayers))
			.opener(Section.ok(new Opener(new GlyphCount(3, 1), Style.MAGIC, first, true, firstResult)))
			.tickLog(Section.ok(new TickLog(List.of(
				new TickLogEntry(40, Style.MAGIC, Actor.SELF, null, List.of()),
				new TickLogEntry(47, Style.RANGED, Actor.SELF, 7, List.of()),
				new TickLogEntry(56, Style.MAGIC, Actor.SELF, 9, List.of("crash line at 50"))), 7)))
			.crashes(Section.ok(new CrashSummary(List.of(
				new CrashLine(50, Phase.P3, Actor.SELF, 1, 1, centre, false, 0),
				new CrashLine(54, Phase.P3, Actor.SELF, 1, 2, centre, true, 12),
				new CrashLine(58, Phase.P3, Actor.SELF, 1, 3, centre, false, 0)))))
			.waves(Section.ok(new WaveSummary(List.of(
				new WaveHit(60, Phase.P3, Actor.SELF, true, 15, true),
				new WaveHit(64, Phase.P3, Actor.SELF, false, 0, false)))))
			.specs(Section.ok(new SpecSummary(List.of(maul), List.of(),
				List.of(new DrainStep(2, Phase.P1, "Elder maul", new YamaStats(225, 250, 0), new YamaStats(147, 250, 0))),
				new YamaStats(225, 250, 0), new YamaStats(147, 250, 0), 147, Phase.P1)))
			.build();
	}
}
